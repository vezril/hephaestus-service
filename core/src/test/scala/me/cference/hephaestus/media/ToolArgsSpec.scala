package me.cference.hephaestus.media

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Exact-argv tests for the tool builders. These pin the command lines the real `MediaTools` execs,
 * and assert there is no shell metacharacter that would need quoting (each element is a discrete
 * argv token).
 */
final class ToolArgsSpec extends AnyFunSuite with Matchers:

  test("vipsHeaderField reads one header field") {
    ToolArgs.vipsHeaderField("/tmp/in.jpg", "width") shouldBe
      Seq("vipsheader", "-f", "width", "/tmp/in.jpg")
  }

  test("vipsThumbnail fits within the long edge (down only) and sets webp quality") {
    ToolArgs.vipsThumbnail("/tmp/in.jpg", "/tmp/thumb.webp", 250) shouldBe
      Seq(
        "vips",
        "thumbnail",
        "/tmp/in.jpg",
        "/tmp/thumb.webp[Q=80]",
        "250",
        "--height",
        "250",
        "--size",
        "down"
      )
  }

  test("vipsThumbnail varies only the long edge between thumb and sample") {
    ToolArgs.vipsThumbnail("/tmp/in.jpg", "/tmp/s.webp", 850) shouldBe
      Seq(
        "vips",
        "thumbnail",
        "/tmp/in.jpg",
        "/tmp/s.webp[Q=80]",
        "850",
        "--height",
        "850",
        "--size",
        "down"
      )
  }

  test("vipsForceSize forces an exact square raster") {
    ToolArgs.vipsForceSize("/tmp/in.png", "/tmp/g.png", 32) shouldBe
      Seq(
        "vips",
        "thumbnail",
        "/tmp/in.png",
        "/tmp/g.png",
        "32",
        "--height",
        "32",
        "--size",
        "force"
      )
  }

  test("vipsColourspaceBW converts to single-band greyscale") {
    ToolArgs.vipsColourspaceBW("/tmp/g.png", "/tmp/g.pgm") shouldBe
      Seq("vips", "colourspace", "/tmp/g.png", "/tmp/g.pgm", "b-w")
  }

  test("ffprobeJson prints json on stdout only") {
    ToolArgs.ffprobeJson("/tmp/v.mp4") shouldBe
      Seq(
        "ffprobe",
        "-v",
        "quiet",
        "-print_format",
        "json",
        "-show_format",
        "-show_streams",
        "/tmp/v.mp4"
      )
  }

  test("ffmpegPoster extracts a single first frame") {
    ToolArgs.ffmpegPoster("/tmp/v.mp4", "/tmp/poster.png") shouldBe
      Seq("ffmpeg", "-y", "-i", "/tmp/v.mp4", "-frames:v", "1", "-update", "1", "/tmp/poster.png")
  }

  test("ffmpegTranscode720p caps height at 720 with even width, h264+aac+faststart") {
    ToolArgs.ffmpegTranscode720p("/tmp/v.mp4", "/tmp/720p.mp4") shouldBe
      Seq(
        "ffmpeg",
        "-y",
        "-i",
        "/tmp/v.mp4",
        "-vf",
        "scale=w=-2:h=min(720\\,ih)",
        "-c:v",
        "libx264",
        "-preset",
        "veryfast",
        "-crf",
        "23",
        "-pix_fmt",
        "yuv420p",
        "-c:a",
        "aac",
        "-movflags",
        "+faststart",
        "/tmp/720p.mp4"
      )
  }

  test("the transcode filter escapes the comma for the filtergraph (no shell quoting)") {
    val vf = ToolArgs.ffmpegTranscode720p("in", "out").sliding(2).collectFirst {
      case Seq("-vf", value) => value
    }
    vf shouldBe Some("scale=w=-2:h=min(720\\,ih)")
  }
