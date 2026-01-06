package csw.proto.galil.hcd

import com.typesafe.config.Config

import java.io.{File, FileWriter, PrintWriter}
import java.nio.file.{Files, Paths}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.io.Source
import scala.util.{Try, Using}

/**
 * Manages DMC program files for upload/download operations
 */
object ProgramFileManager {
  
  /**
   * Read a DMC program file from resources or runtime directory
   * 
   * @param filename Name of the DMC file (e.g., "protoHCD_lab.dmc")
   * @param config Configuration containing program paths
   * @return Program text content
   */
  def readProgramFile(filename: String, config: Config): Try[String] = Try {
    // First try runtime directory (for previously downloaded files)
    val runtimeDir = if (config.hasPath("programs.runtime")) {
      config.getString("programs.runtime")
    } else {
      "programs"
    }
    
    val runtimePath = Paths.get(runtimeDir, filename)
    
    if (Files.exists(runtimePath)) {
      // Read from runtime directory
      Using(Source.fromFile(runtimePath.toFile)) { source =>
        source.mkString
      }.get
    } else {
      // Try reading from resources (known-good programs)
      val resourcesPath = if (config.hasPath("programs.resources")) {
        config.getString("programs.resources")
      } else {
        "programs"
      }
      
      val resourceStream = getClass.getClassLoader.getResourceAsStream(s"$resourcesPath/$filename")
      if (resourceStream == null) {
        throw new RuntimeException(
          s"Program file not found: $filename (checked runtime: $runtimePath and resources: $resourcesPath)"
        )
      }
      
      Using(Source.fromInputStream(resourceStream)) { source =>
        source.mkString
      }.get
    }
  }
  
  /**
   * Write program text to a file in the runtime directory
   * 
   * @param filename Name of the DMC file to create
   * @param programText Content to write
   * @param config Configuration containing program paths
   * @return Path to the created file
   */
  def writeProgramFile(filename: String, programText: String, config: Config): Try[String] = Try {
    val runtimeDir = if (config.hasPath("programs.runtime")) {
      config.getString("programs.runtime")
    } else {
      "programs"
    }
    
    // Create directory if it doesn't exist
    val dir = new File(runtimeDir)
    if (!dir.exists()) {
      dir.mkdirs()
    }
    
    val filePath = Paths.get(runtimeDir, filename)
    
    // Write the file
    Using(new PrintWriter(new FileWriter(filePath.toFile))) { writer =>
      writer.write(programText)
    }.get
    
    filePath.toString
  }
  
  /**
   * Create a timestamped backup filename
   * 
   * @param prefix Prefix for the filename (e.g., "backup")
   * @return Filename with timestamp (e.g., "backup_20241217_143022.dmc")
   */
  def createTimestampedFilename(prefix: String = "backup"): String = {
    val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    val timestamp = LocalDateTime.now().format(formatter)
    s"${prefix}_$timestamp.dmc"
  }
  
  /**
   * Process DMC program text for upload:
   * - Strips REM comments (lines starting with REM)
   * - Removes empty lines
   * - Preserves indentation (leading whitespace)
   * - Preserves inline ' comments (controller accepts these)
   * - Removes trailing whitespace
   * - Ensures proper line endings
   * 
   * @param programText Raw program text (may include comments)
   * @return Cleaned program text ready for upload
   */
  def prepareProgramForUpload(programText: String): String = {
    programText
      .split("\n")
      .map(_.replaceAll("\\s+$", ""))        // Remove TRAILING whitespace only
      .filter(_.trim.nonEmpty)                // Remove blank lines
      .filterNot(_.trim.startsWith("REM"))    // Remove REM comments
      .mkString("\r\n")  // Galil expects CR+LF
  }
  
  /**
   * Parse LS response from controller
   * Galil returns programs with line numbers like:
   * 0 #PROG1
   * 1 line1
   * 2 line2
   * ...
   * 
   * @param lsResponse Raw LS command response
   * @return Cleaned program text without line numbers
   */
  def parseDownloadedPrograms(lsResponse: String): String = {
    lsResponse
      .split("\n")
      .map(_.trim)
      .filter(_.nonEmpty)
      .filter(_ != ":")  // Remove Galil's response terminators
      .map { line =>
        // Remove line numbers (e.g., "0 #AUTO" -> "#AUTO")
        val spaceIndex = line.indexOf(' ')
        if (spaceIndex > 0 && line.substring(0, spaceIndex).forall(_.isDigit)) {
          line.substring(spaceIndex + 1)
        } else {
          line
        }
      }
      .mkString("\n")
  }
}