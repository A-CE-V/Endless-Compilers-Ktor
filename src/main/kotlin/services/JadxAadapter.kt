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
}