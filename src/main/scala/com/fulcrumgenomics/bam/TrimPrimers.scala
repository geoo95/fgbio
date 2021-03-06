/*
 * The MIT License
 *
 * Copyright (c) 2016 Fulcrum Genomics LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.fulcrumgenomics.bam

import java.lang.Math.abs

import com.fulcrumgenomics.FgBioDef._
import com.fulcrumgenomics.bam.api.{SamOrder, SamRecord, SamSource, SamWriter}
import com.fulcrumgenomics.cmdline.{ClpGroups, FgBioTool}
import com.fulcrumgenomics.commons.util.{DelimitedDataParser, LazyLogging}
import com.fulcrumgenomics.sopt.{arg, clp}
import com.fulcrumgenomics.util.{Io, ProgressLogger}
import htsjdk.samtools.SAMFileHeader.SortOrder
import htsjdk.samtools.SamPairUtil.PairOrientation
import htsjdk.samtools._
import htsjdk.samtools.reference.ReferenceSequenceFileWalker
import htsjdk.samtools.util._

import scala.collection.BufferedIterator

object TrimPrimers {
  val Headers: Seq[String] = Seq("chrom", "left_start", "left_end", "right_start", "right_end")
  val Seq(hdChrom, hdleftStart, hdLeftEnd, hdRightStart, hdRightEnd) = Headers
}

@clp(group=ClpGroups.SamOrBam, description=
  """
    |Trims primers from reads post-alignment.  Takes in a BAM file of aligned reads
    |and a tab-delimited file with five columns (`chrom`, `left_start`, `left_end`,
    |`right_start`, and `right_end`) which provide the 1-based inclusive start and end
    |positions of the primers for each amplicon.  The primer file must include headers, e.g:
    |
    |```
    |chrom  left_start  left_end  right_start right_end
    |chr1   1010873     1010894   1011118     1011137
    |```
    |
    |Paired end reads that map to a given amplicon position are trimmed so that the
    |alignment no-longer includes the primer sequences. All other aligned reads have the
    |_maximum primer length trimmed_!
    |
    |Reads that are trimmed will have the `NM`, `UQ` and `MD` tags cleared as they are no longer
    |guaranteed to be accurate.  If a reference is provided the reads will be re-sorted
    |by coordinate after trimming and the `NM`, `UQ` and `MD` tags recalculated.
    |
    |If the input BAM is not `queryname` sorted it will be sorted internally so that mate
    |information between paired-end reads can be corrected before writing the output file.
  """)
class TrimPrimers
( @arg(flag='i', doc="Input BAM file.")  val input: PathToBam,
  @arg(flag='o', doc="Output BAM file.") val output: PathToBam,
  @arg(flag='p', doc="File with primer locations.") val primers: FilePath,
  @arg(flag='H', doc="If true, hard clip reads, else soft clip.") val hardClip: Boolean = false,
  @arg(flag='S', doc="Match to primer locations +/- this many bases.") val slop: Int = 5,
  @arg(flag='s', doc="Sort order of output BAM file (defaults to input sort order).") val sortOrder: Option[SamOrder] = None,
  @arg(flag='r', doc="Optional reference fasta for recalculating NM, MD and UQ tags.") val ref: Option[PathToFasta] = None,
  @arg(flag='a', doc="Automatically trim extended attributes that are the same length as bases.") val autoTrimAttributes: Boolean = false
)extends FgBioTool with LazyLogging {
  private val clipper = new SamRecordClipper(mode=if (hardClip) ClippingMode.Hard else ClippingMode.Soft, autoClipAttributes=autoTrimAttributes)

  Io.assertReadable(input)
  Io.assertReadable(primers)
  Io.assertCanWriteFile(output)

  /** A Locatable Amplicon class. */
  private case class Amplicon(chrom: String, leftStart: Int, leftEnd: Int, rightStart: Int, rightEnd: Int) extends Locatable {
    def leftPrimerLength: Int    = CoordMath.getLength(leftStart, leftEnd)
    def rightPrimerLength: Int   = CoordMath.getLength(rightStart, rightEnd)
    def longestPrimerLength: Int = Math.max(leftPrimerLength, rightPrimerLength)

    override def getContig: String = chrom
    override def getStart: Int = leftStart
    override def getEnd: Int = rightEnd
  }

  override def execute(): Unit = {
    val in = SamSource(input)
    val isSortOrder = SamOrder(in.header)
    val outSortOrder = sortOrder.orElse(SamOrder(in.header)).getOrElse(SamOrder.Unknown)
    val outHeader = in.header.clone()
    outSortOrder.applyTo(outHeader)

    // Setup the outputs depending on whether or not we have a reference file or not
    // In order to minimize (ha!) the amount of sorting going on, things are a little complex.
    // The logic is more or less as follows:
    //   a) For trimming we need things in queryname order, so if input isn't queryname sorted, sort it
    //   b) Then, if we were given a reference, we need to coordinate sort to reset the MD, NM, UQ tags
    //   c) Then finally for output, the SAMFileWriter will see things as pre-sorted if:
    //         i) No reference was given (so no coordinate sort) and the output sort order is queryname, or
    //        ii) A reference was given (so the last sort is coordinate) and the output sort order is coordinate
    //      else, we'll have to sort for potentially a third time in the SAMFileWriter
    val (sorter, write: (SamRecord => Any), out: SamWriter) = ref match {
      case Some(path) =>
        val sorter = Bams.sorter(SamOrder.Coordinate, outHeader)
        val out = SamWriter(output, outHeader, sort= if (outSortOrder == SamOrder.Coordinate) None else Some(outSortOrder))
        (Some(sorter), sorter.write _, out)
      case None =>
        val out = SamWriter(output, outHeader, sort= if (outSortOrder == SamOrder.Queryname) None else Some(outSortOrder))
        (None, out += _, out)
    }

    val detector = loadPrimerFile(primers)
    val maxPrimerLength = detector.getAll.map(_.longestPrimerLength).max

    // Main processing loop
    val iterator = queryNameOrderIterator(in)
    val trimProgress = ProgressLogger(this.logger, "Trimmed")
    while (iterator.hasNext) {
      val reads = nextTemplate(iterator)
      trimReadsForTemplate(detector, maxPrimerLength, reads)
      reads.foreach(write)
      reads.foreach(trimProgress.record)
    }

    // If we had a reference and re-sorted above, reset the NM/UQ/MD tags as we push to the final output
    (sorter, ref) match {
      case (Some(sorter), Some(path)) =>
        val walker = new ReferenceSequenceFileWalker(path.toFile)
        val progress = ProgressLogger(this.logger, "Written")

        sorter.foreach { rec =>
          recalculateTags(rec, walker)
          out += rec
          progress.record(rec)
        }

        sorter.safelyClose()
      case _ => ()
    }

    out.close()
  }

  /** Recalculates the MD, NM, and UQ tags on aligned records. */
  def recalculateTags(rec: SamRecord, walker: ReferenceSequenceFileWalker): Unit = {
    if (rec.mapped) {
      val refBases = walker.get(rec.refIndex).getBases
      SequenceUtil.calculateMdAndNmTags(rec.asSam, refBases, true, true)
      if (rec.quals != null && rec.quals.length != 0) {
        rec(SAMTag.UQ.name) = SequenceUtil.sumQualitiesOfMismatches(rec.asSam, refBases, 0)
      }
    }
  }

  /** Trims all the reads for a given template. */
  def trimReadsForTemplate(detector: OverlapDetector[Amplicon], maxPrimerLength: Int, reads: Seq[SamRecord]): Unit = {
    val rec1 = reads.find(r => r.paired && r.firstOfPair  && !r.secondary && !r.supplementary)
    val rec2 = reads.find(r => r.paired && r.secondOfPair && !r.secondary && !r.supplementary)

    (rec1, rec2) match {
      case (Some(r1), Some(r2)) =>
        // FR mapped pairs get the full treatment
        if (r1.mapped && r2.mapped && r1.refIndex == r2.refIndex && r1.pairOrientation == PairOrientation.FR) {
          val (left, right) = if (r1.negativeStrand) (r2, r1) else (r1, r2)
          val (start, end) = (left.unclippedStart, right.unclippedEnd)
          val insert = new Interval(left.refName, start, end)
          detector.getOverlaps(insert).find(amp => abs(amp.leftStart - start) <= slop && abs(amp.rightEnd - end) <= slop) match {
            case Some(amplicon) =>
              val leftClip = amplicon.leftPrimerLength
              val rightClip = amplicon.rightPrimerLength
              reads.foreach { rec =>
                val toClip = if (rec.firstOfPair == left.firstOfPair) leftClip else rightClip
                this.clipper.clip5PrimeEndOfRead(rec, toClip)
              }
            case None =>
              reads.foreach(r => this.clipper.clip5PrimeEndOfRead(r, maxPrimerLength))
          }

          clipFullyOverlappedFrReads(r1, r2)
        }
        // Pairs without both reads mapped in FR orientation are just maximally clipped
        else {
          reads.foreach(r => this.clipper.clip5PrimeEndOfRead(r, maxPrimerLength))
        }

        SamPairUtil.setMateInfo(r1.asSam, r2.asSam, true)
        reads.filter(_.supplementary).foreach { rec =>
          val mate = if (rec.firstOfPair) r2 else r1
          SamPairUtil.setMateInformationOnSupplementalAlignment(rec.asSam, mate.asSam, true);
        }
      case _ =>
        // Just trim each read independently
        reads.foreach(r => this.clipper.clip5PrimeEndOfRead(r, maxPrimerLength))
    }
  }

  /** Gets an iterator in query name order over the records. */
  private def queryNameOrderIterator(in: SamSource): BufferedIterator[SamRecord] = {
    if (in.header.getSortOrder == SortOrder.queryname) {
      in.iterator.bufferBetter
    }
    else {
      logger.info("Sorting into queryname order.")
      val progress = ProgressLogger(this.logger, "Queryname sorted")
      val sorter = Bams.sorter(SamOrder.Queryname, in.header)
      in.foreach { rec =>
        sorter += rec
        progress.record(rec)
      }
      sorter.iterator
    }
  }

  /** Fetches the next group of records that all share the same readname/template from the iterator. */
  private def nextTemplate(iterator: BufferedIterator[SamRecord]): Seq[SamRecord] = {
    val first    = iterator.next()
    val template = first.name
    first :: iterator.takeWhile(_.name == template).toList
  }

  /** Creates an overlap detector for all the amplicons from the input file. */
  private def loadPrimerFile(path: FilePath): OverlapDetector[Amplicon] = {
    val parser = DelimitedDataParser(path, '\t')
    TrimPrimers.Headers.foreach { h => require(parser.headers.contains(h), s"Could not find column header '$h' in $path.") }
    require(parser.hasNext, "Primer file contained no data.")
    require(parser.headers.contains("chrom"), "Could not find column header 'chrom'")
    
    val detector = new OverlapDetector[Amplicon](0,0)
    parser.foreach { row =>
      val amp = Amplicon(
        chrom      = row[String](TrimPrimers.hdChrom),
        leftStart  = row[Int](TrimPrimers.hdleftStart),
        leftEnd    = row[Int](TrimPrimers.hdLeftEnd),
        rightStart = row[Int](TrimPrimers.hdRightStart),
        rightEnd   = row[Int](TrimPrimers.hdRightEnd)
      )

      detector.addLhs(amp, amp)
    }

    detector
  }

  /**
    * Adapted from Picard's AbstractAlignmentMerger - takes in mapped FR pairs only
    * and clips from the 3' ends of the reads if the reads are fully overlapped
    * and extend past each other's starts.
    */
  private def clipFullyOverlappedFrReads(r1: SamRecord, r2: SamRecord): Unit = {
    val (plus, minus) = if (r1.negativeStrand) (r2,r1) else (r1, r2)

    if (plus.start < minus.end) {
      val plusTrim  = plus.end   - minus.end
      val minusTrim = plus.start - minus.start

      if (plusTrim  > 0) this.clipper.clip3PrimeEndOfAlignment(plus, plusTrim)
      if (minusTrim > 0) this.clipper.clip3PrimeEndOfAlignment(minus, minusTrim)
    }
  }
}
