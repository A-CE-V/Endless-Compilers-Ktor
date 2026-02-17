package org.endless.services

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import java.io.File
import java.nio.file.Files

class JadxAdapter {
    fun decompileClass(classBytes: ByteArray): String {
        val tmp = Files.createTempFile("jadx-in-", ".class").toFile()
        Files.write(tmp.toPath(), classBytes)

        val args = JadxArgs()
        args.inputFiles.add(tmp)
        args.isSkipResources = true
        args.threadsCount = 1 // CRITICAL for Free Tier

        try {
            JadxDecompiler(args).use { jadx ->
                jadx.load()
                val sb = StringBuilder()
                for (cls in jadx.classes) {
                    sb.append(cls.code).append("\n")
                }
                return sb.toString()
            }
        } finally {
            tmp.delete()
        }
    }

    fun decompileJar(jarFile: File, outputDir: File) {
        val args = JadxArgs().apply {
            inputFiles.add(jarFile)
            outDir = outputDir // Jadx will recreate the package structure here
            isSkipResources = true
            threadsCount = 1 // Keeps Render from crashing
        }
        JadxDecompiler(args).use { jadx ->
            jadx.load()
            jadx.save() // This writes all .java files to the disk
        }
    }
}