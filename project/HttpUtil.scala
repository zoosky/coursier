import java.io.{ByteArrayOutputStream, InputStream}
import java.net.{HttpURLConnection, URL, URLConnection}
import java.nio.charset.StandardCharsets

import sbt.Logger

object HttpUtil {

  private def readFully(is: InputStream): Array[Byte] = {
    val buffer = new ByteArrayOutputStream
    val data = Array.ofDim[Byte](16384)

    var nRead = 0
    while ({
      nRead = is.read(data, 0, data.length)
      nRead != -1
    })
      buffer.write(data, 0, nRead)

    buffer.flush()
    buffer.toByteArray
  }

  def fetch(url: String, log: Logger): String = {

    val url0 = new URL(url)

    log.info(s"Fetching $url")

    val (rawResp, code) = {

      var conn: URLConnection = null
      var httpConn: HttpURLConnection = null
      var is: InputStream = null

      try {
        conn = url0.openConnection()
        httpConn = conn.asInstanceOf[HttpURLConnection]
        httpConn.setRequestProperty("Accept", "application/vnd.travis-ci.2+json")
        is = conn.getInputStream

        (readFully(is), httpConn.getResponseCode)
      } finally {
        if (is != null)
          is.close()
        if (httpConn != null)
          httpConn.disconnect()
      }
    }

    if (code / 100 != 2)
      sys.error(s"Unexpected response code when getting $url: $code")

    new String(rawResp, StandardCharsets.UTF_8)
  }

}
