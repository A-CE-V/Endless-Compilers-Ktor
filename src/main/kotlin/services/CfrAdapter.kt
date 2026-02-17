package org.endless.services

import org.benf.cfr.reader.api.CfrDriver
import org.benf.cfr.reader.api.OutputSinkFactory
import org.benf.cfr.reader.api.SinkReturns
import java.io.File
import java.io.FileOutputStream
import java.util.*

class CfrAdapter {

    fun decompileClass(classBytes: ByteArray, providedClassName: String?): String {
        // Create temp file efficiently
        val tmp = File.createTempFile("cfr-class-", ".class")
        FileOutputStream(tmp).use { it.write(classBytes) }

        val sb = StringBuilder()

        // Sink Factory (Direct copy of your logic, adapted to Kotlin)
        val mySink = object : OutputSinkFactory {
            override fun getSupportedSinks(sinkType: OutputSinkFactory.SinkType, available: Collection<OutputSinkFactory.SinkClass>): List<OutputSinkFactory.SinkClass> {
                return if (sinkType == OutputSinkFactory.SinkType.JAVA)
                    listOf(OutputSinkFactory.SinkClass.DECOMPILED, OutputSinkFactory.SinkClass.STRING)
                else
                    listOf(OutputSinkFactory.SinkClass.STRING)
            }

            override fun <T> getSink(sinkType: OutputSinkFactory.SinkType, sinkClass: OutputSinkFactory.SinkClass): OutputSinkFactory.Sink<T> {
                if (sinkType == OutputSinkFactory.SinkType.JAVA && sinkClass == OutputSinkFactory.SinkClass.DECOMPILED) {
                    return OutputSinkFactory.Sink { x ->
                        if (x is SinkReturns.Decompiled) {
                            sb.append("/* Package: ${x.packageName} Class: ${x.className} */\n")
                            sb.append(x.java).append("\n")
                        }
                    }
                }
                return OutputSinkFactory.Sink {}
            }
        }

        val driver = CfrDriver.Builder().withOutputSink(mySink).build()
        driver.analyse(listOf(tmp.absolutePath))

        tmp.delete() // Cleanup
        return sb.toString()
    }
}