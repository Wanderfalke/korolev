package korolev.server

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import korolev.FormData

import scala.annotation.tailrec

/**
  * @author Aleksey Fomkin <aleksey.fomkin@gmail.com>
  */
final class FormDataCodec(maxPartSize: Int) {

  import FormDataCodec._

  private val threadLocalBuffer = new ThreadLocal[ByteBuffer] {
    override def initialValue(): ByteBuffer = {
      ByteBuffer.allocate(maxPartSize)
    }
  }

  def decode(source: ByteBuffer, boundary: String): FormData = {

    val boundaryWithBreaks = s"\n--$boundary\n"
    val end = s"\n--$boundary--\n"
    val buffer = threadLocalBuffer.get()

    buffer.clear()

    // Check the delimiter is reached
    def checkDelimiter(delimiter: String): Boolean = {
      val d = delimiter.getBytes
      @tailrec def aux(pos: Int, startPos: Int): Boolean = {
        if (pos < d.length) {
          val b = source.get()
          if (b == '\r') aux(pos, startPos)
          else if (b == d(pos)) aux(pos + 1, startPos)
          else {
            source.position(startPos)
            false
          }
        } else true
      }
      aux(0, source.position)
    }

    type Entries = List[FormData.Entry]
    type Headers = List[(String, String)]

    def loop(entries: Entries,
             headers: Headers,
             state: DecoderState): Entries = {

      def updateEntries() = {
        val content = {
          val bytes = new Array[Byte](buffer.position)
          buffer.rewind()
          buffer.get(bytes)
          buffer.clear()
          ByteBuffer.wrap(bytes)
        }
        val nameOpt = headers collectFirst {
          case (key, value) if key.toLowerCase == "content-disposition" =>
            value
        } flatMap { contentDisposition =>
          contentDisposition.split(';') collectFirst {
            case s if s.indexOf('=') + s.indexOf("name") > -1 =>
              val Array(_, value) = s.split('=')
              value.stripPrefix("\"").stripSuffix("\"")
          }
        }
        nameOpt.fold(entries) { name =>
          val newEntry = FormData.Entry(name, content, headers)
          newEntry :: entries
        }
      }

      state match {
        case Buffering if checkDelimiter(end) => updateEntries()
        case Buffering if checkDelimiter(boundaryWithBreaks) =>
          val ue = updateEntries()
          loop(ue, Nil, Headers)
        case Headers if checkDelimiter("\n\n") =>
          val bytes = new Array[Byte](buffer.position)
          buffer.rewind()
          buffer.get(bytes)
          val rawHeaders = new String(bytes, StandardCharsets.ISO_8859_1)
          val headers = rawHeaders.split('\n').toList map { line =>
            val Array(key, value) = line.split(':')
            key.trim -> value.trim
          }
          buffer.clear()
          loop(entries, headers, Buffering)
        case Buffering | Headers =>
          buffer.put(source.get())
          loop(entries, headers, state)
      }
    }

    checkDelimiter(s"--$boundary\n")
    FormData(loop(Nil, Nil, Headers))
  }
}

object FormDataCodec {
  private sealed trait DecoderState
  private case object Buffering extends DecoderState
  private case object Headers extends DecoderState
}
