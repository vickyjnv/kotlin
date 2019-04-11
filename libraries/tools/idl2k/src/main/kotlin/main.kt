package org.jetbrains.idl2k

import org.jetbrains.idl2k.util.readCopyrightNoticeFromProfile
import java.io.*

fun main(args: Array<String>) {
    val webIdl = BuildWebIdl(
            mdnCacheFile = File("target/mdn-cache.txt"),
            srcDir = File("../../stdlib/js-common/idl"))

    println("Generating...")

    val copyrightNotice = readCopyrightNoticeFromProfile(File("../../../.idea/copyright/apache.xml"))
    webIdl.jsGenerator(File("../../stdlib/js-common/src/org.w3c"), copyrightNotice)
}
