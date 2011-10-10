package scalash

import scala.io.Source._

trait Readify {
    /**
     * File reading helper.
     *
     * @param path Path to file of interest.
     * @param readUntil Optional function which indicates when we're no longer
     * interested in reading any more of the file.
     * @param fun Function to pass theÂ     */
    def readify(filePath: String, readUntil: String => Boolean = (s: String) => true)(fun: String => Any) {
        val fileIter = fromFile(filePath)
        fileIter.getLines.toStream.takeWhile(readUntil(_)).foreach(fun(_))
        fileIter.close // Don't leave the file open.
    }

    /**
     * Read an entire file into memory at once.  This could be dangerous, use
     * with caution.
     */
    def catify(filePath: String): String = {
        val fileIter = fromFile(filePath)
        val out = fileIter.mkString
        fileIter.close // Don't leave the file open.
        out
    }
}

