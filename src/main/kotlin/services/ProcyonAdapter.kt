package org.endless.services

import com.strobel.assembler.metadata.ArrayTypeLoader
import com.strobel.decompiler.Decompiler
import com.strobel.decompiler.DecompilerSettings
import com.strobel.decompiler.PlainTextOutput
import java.io.StringWriter

class ProcyonAdapter {
    fun decompileClass(classBytes: ByteArray, className: String?): String {
        val writer = StringWriter()
        val settings = DecompilerSettings.javaDefaults()

        // Load directly from RAM, no temp file needed!
        settings.typeLoader = ArrayTypeLoader(classBytes)

        val internalName = className?.replace('.', '/') ?: "Generated"
        Decompiler.decompile(internalName, PlainTextOutput(writer), settings)

        return writer.toString()
    }
}