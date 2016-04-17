package de.nierbeck.cassandra.embedded

import java.io.{ File, FileOutputStream, IOException }
import java.net.{ URLClassLoader, URL, InetAddress }

import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import java.net.URI
import java.io.InputStream
import java.util.HashMap
import scala.collection.JavaConverters._

object CassandraRunner {
  import java.io._
  import java.net.{ InetAddress, Socket }

  import scala.io.Source
  import scala.language.reflectiveCalls
  import scala.util.Try

  private val log = LoggerFactory.getLogger(this.getClass)

  /** Makes a new directory or throws an `IOException` if it cannot be made */
  def mkdir(dir: File): File = {
    if (!dir.mkdir()) throw new IOException(s"Could not create dir $dir")
    dir
  }

  /**
   * Automatically closes resource after use. Handy for closing streams, files, sessions etc.
   * Similar to try-with-resources in Java 7.
   */
  def closeAfterUse[T, C <: { def close() }](closeable: C)(code: C => T): T =
    try code(closeable) finally {
      closeable.close()
    }

  /** Copies a text file substituting every occurrence of `$ {VARIABLE}` with a value from the given map */
  def copyTextFileWithVariableSubstitution(source: InputStream, target: OutputStream, map: String => String) {
    val regex = "\\$\\{([a-zA-Z0-9_]+)\\}".r
    closeAfterUse(new PrintWriter(target)) { writer =>
      val input = Source.fromInputStream(source, "UTF-8")
      for (line <- input.getLines()) {
        val substituted = regex.replaceAllIn(line, m => map(m.group(1)))
        writer.println(substituted)
      }
    }
  }

  /**
   * Waits until a port at the given address is open or timeout passes.
   *
   * @return true if managed to connect to the port, false if timeout happened first
   */
  def waitForPortOpen(host: InetAddress, port: Int, timeout: Long): Boolean = {
    val startTime = System.currentTimeMillis()
    val portProbe = Iterator.continually {
      Try {
        Thread.sleep(100)
        val socket = new Socket(host, port)
        socket.close()
      }
    }
    portProbe
      .dropWhile(p => p.isFailure && System.currentTimeMillis() - startTime < timeout)
      .next()
      .isSuccess
  }

  val SizeEstimatesUpdateIntervalInSeconds = 5
  val DefaultJmxPort = 7199
}

class CassandraRunner(val configurationSource: InputStream, props: HashMap[String, String], runForked: Boolean = true) {

  import CassandraRunner._

  private val properties = props.asScala
  
  val startupTime = System.currentTimeMillis()

  var buffer = new StringBuffer
  val urls = getClass.getClassLoader.asInstanceOf[URLClassLoader].getURLs
  urls.foreach(url => buffer.append(new File(url.getPath)).append(System.getProperty("path.separator")))
  val classPath = buffer.toString

  val destination: File = new File("data/cassandra.yaml")
  FileUtils.copyInputStreamToFile(configurationSource, destination)
  
  log.debug(s"Classpath: ${classPath}")

  private val javaBin = System.getProperty("java.home") + "/bin/java"
  private val cassandraConfProperty = "-Dcassandra.config=file:" + destination.getAbsolutePath.toString
  private val superuserSetupDelayProperty = "-Dcassandra.superuser_setup_delay_ms=0"
  val jmxPort = properties.getOrElse("jmx_port", DefaultJmxPort.toString).toInt
  private val jmxPortProperty = s"-Dcassandra.jmx.local.port=$jmxPort"
  private val host = properties.getOrElse("listen_address", "127.0.0.1")
  private val sizeEstimatesUpdateIntervalProperty =
    s"-Dcassandra.size_recorder_interval=$SizeEstimatesUpdateIntervalInSeconds"
  //  private val logConfigFileProperty = s"-Dlog4j.configuration=${getClass.getClassLoader.getResource("/cassandra-log4j.properties").toString}"
  private val cassandraMainClass = "org.apache.cassandra.service.CassandraDaemon"

  private val process = new ProcessBuilder()
    .command(
      javaBin,
      "-Xms512M", "-Xmx1G", "-Xmn384M", "-XX:+UseConcMarkSweepGC",
      sizeEstimatesUpdateIntervalProperty,
      cassandraConfProperty, superuserSetupDelayProperty, jmxPortProperty, //logConfigFileProperty,
      "-cp", classPath, cassandraMainClass, "-f"
    )
    .inheritIO()
    .start()

  val nativePort = properties.get("native_transport_port").get.toInt

  if (!waitForPortOpen(InetAddress.getByName(properties.get("rpc_address").get), nativePort, 100000))
    throw new IOException("Failed to start Cassandra.")

  def destroy() {
    process.destroy()
    process.waitFor()
  }

}
