package cfig.bootimg.v2

import cfig.Avb
import cfig.Helper
import cfig.bootimg.Common
import cfig.bootimg.Common.Companion.deleleIfExists
import cfig.bootimg.Common.Slice
import cfig.bootimg.Signer
import cfig.packable.VBMetaParser
import com.fasterxml.jackson.databind.ObjectMapper
import de.vandermeer.asciitable.AsciiTable
import org.apache.commons.exec.CommandLine
import org.apache.commons.exec.DefaultExecutor
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalUnsignedTypes::class)
data class BootV2(
        var info: MiscInfo = MiscInfo(),
        var kernel: CommArgs = CommArgs(),
        var ramdisk: CommArgs = CommArgs(),
        var secondBootloader: CommArgs? = null,
        var recoveryDtbo: CommArgsLong? = null,
        var dtb: CommArgsLong? = null
) {
    data class MiscInfo(
            var output: String = "",
            var json: String = "",
            var headerVersion: UInt = 0U,
            var headerSize: UInt = 0U,
            var loadBase: UInt = 0U,
            var tagsOffset: UInt = 0U,
            var board: String? = null,
            var pageSize: UInt = 0U,
            var cmdline: String = "",
            var osVersion: String? = null,
            var osPatchLevel: String? = null,
            var hash: ByteArray? = byteArrayOf(),
            var verify: String = "",
            var imageSize: Long = 0)

    data class CommArgs(
            var file: String? = null,
            var position: UInt = 0U,
            var size: Int = 0,
            var loadOffset: UInt = 0U)

    data class CommArgsLong(
            var file: String? = null,
            var position: UInt = 0U,
            var size: UInt = 0U,
            var loadOffset: ULong = 0U)

    companion object {
        private val log = LoggerFactory.getLogger(BootV2::class.java)
        private val workDir = Helper.prop("workDir")

        fun parse(fileName: String): BootV2 {
            val ret = BootV2()
            FileInputStream(fileName).use { fis ->
                val bh2 = BootHeaderV2(fis)
                ret.info.let { theInfo ->
                    theInfo.output = File(fileName).name
                    theInfo.json = File(fileName).name.removeSuffix(".img") + ".json"
                    theInfo.pageSize = bh2.pageSize
                    theInfo.headerSize = bh2.headerSize
                    theInfo.headerVersion = bh2.headerVersion
                    theInfo.board = bh2.board
                    theInfo.cmdline = bh2.cmdline
                    theInfo.imageSize = File(fileName).length()
                    theInfo.tagsOffset = bh2.tagsOffset
                    theInfo.hash = bh2.hash
                    theInfo.osVersion = bh2.osVersion
                    theInfo.osPatchLevel = bh2.osPatchLevel
                    if (Avb.hasAvbFooter(fileName)) {
                        theInfo.verify = "VB2.0"
                        Avb.verifyAVBIntegrity(fileName, Helper.prop("avbtool"))
                    } else {
                        theInfo.verify = "VB1.0"
                    }
                }
                ret.kernel.let { theKernel ->
                    theKernel.file = "${workDir}kernel"
                    theKernel.size = bh2.kernelLength.toInt()
                    theKernel.loadOffset = bh2.kernelOffset
                    theKernel.position = ret.getKernelPosition()
                }
                ret.ramdisk.let { theRamdisk ->
                    theRamdisk.size = bh2.ramdiskLength.toInt()
                    theRamdisk.loadOffset = bh2.ramdiskOffset
                    theRamdisk.position = ret.getRamdiskPosition()
                    if (bh2.ramdiskLength > 0U) {
                        theRamdisk.file = "${workDir}ramdisk.img.gz"
                    }
                }
                if (bh2.secondBootloaderLength > 0U) {
                    ret.secondBootloader = CommArgs()
                    ret.secondBootloader!!.size = bh2.secondBootloaderLength.toInt()
                    ret.secondBootloader!!.loadOffset = bh2.secondBootloaderOffset
                    ret.secondBootloader!!.file = "${workDir}second"
                    ret.secondBootloader!!.position = ret.getSecondBootloaderPosition()
                }
                if (bh2.recoveryDtboLength > 0U) {
                    ret.recoveryDtbo = CommArgsLong()
                    ret.recoveryDtbo!!.size = bh2.recoveryDtboLength
                    ret.recoveryDtbo!!.loadOffset = bh2.recoveryDtboOffset //Q
                    ret.recoveryDtbo!!.file = "${workDir}recoveryDtbo"
                    ret.recoveryDtbo!!.position = ret.getRecoveryDtboPosition().toUInt()
                }
                if (bh2.dtbLength > 0U) {
                    ret.dtb = CommArgsLong()
                    ret.dtb!!.size = bh2.dtbLength
                    ret.dtb!!.loadOffset = bh2.dtbOffset //Q
                    ret.dtb!!.file = "${workDir}dtb"
                    ret.dtb!!.position = ret.getDtbPosition()
                }
            }
            return ret
        }
    }

    private fun getHeaderSize(pageSize: UInt): UInt {
        val pad = (pageSize - (1648U and (pageSize - 1U))) and (pageSize - 1U)
        return pad + 1648U
    }

    private fun getKernelPosition(): UInt {
        return getHeaderSize(info.pageSize)
    }

    private fun getRamdiskPosition(): UInt {
        return (getKernelPosition() + kernel.size.toUInt() +
                Common.getPaddingSize(kernel.size.toUInt(), info.pageSize))
    }

    private fun getSecondBootloaderPosition(): UInt {
        return getRamdiskPosition() + ramdisk.size.toUInt() +
                Common.getPaddingSize(ramdisk.size.toUInt(), info.pageSize)
    }

    private fun getRecoveryDtboPosition(): UInt {
        return if (this.secondBootloader == null) {
            getSecondBootloaderPosition()
        } else {
            getSecondBootloaderPosition() + secondBootloader!!.size.toUInt() +
                    Common.getPaddingSize(secondBootloader!!.size.toUInt(), info.pageSize)
        }
    }

    private fun getDtbPosition(): UInt {
        return if (this.recoveryDtbo == null) {
            getRecoveryDtboPosition()
        } else {
            getRecoveryDtboPosition() + recoveryDtbo!!.size +
                    Common.getPaddingSize(recoveryDtbo!!.size, info.pageSize)
        }
    }

    fun extractImages(): BootV2 {
        val workDir = Helper.prop("workDir")
        //info
        ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(File(workDir + info.json), this)
        //kernel
        Common.dumpKernel(Slice(info.output, kernel.position.toInt(), kernel.size, kernel.file!!))
        //ramdisk
        if (this.ramdisk.size > 0) {
            Common.dumpRamdisk(Slice(info.output, ramdisk.position.toInt(), ramdisk.size, ramdisk.file!!),
                    "${workDir}root")
        }
        //second bootloader
        secondBootloader?.let {
            Helper.extractFile(info.output,
                    secondBootloader!!.file!!,
                    secondBootloader!!.position.toLong(),
                    secondBootloader!!.size)
        }
        //recovery dtbo
        recoveryDtbo?.let {
            Helper.extractFile(info.output,
                    recoveryDtbo!!.file!!,
                    recoveryDtbo!!.position.toLong(),
                    recoveryDtbo!!.size.toInt())
        }
        //dtb
        this.dtb?.let { _ ->
            Common.dumpDtb(Slice(info.output, dtb!!.position.toInt(), dtb!!.size.toInt(), dtb!!.file!!))
        }

        return this
    }

    fun extractVBMeta(): BootV2 {
        Avb().parseVbMeta(info.output)
        if (File("vbmeta.img").exists()) {
            log.warn("Found vbmeta.img, parsing ...")
            VBMetaParser().unpack("vbmeta.img")
        }
        return this
    }

    fun printSummary(): BootV2 {
        val workDir = Helper.prop("workDir")
        val tableHeader = AsciiTable().apply {
            addRule()
            addRow("What", "Where")
            addRule()
        }
        val tab = AsciiTable().let {
            it.addRule()
            it.addRow("image info", workDir + info.output.removeSuffix(".img") + ".json")
            if (this.info.verify == "VB2.0") {
                it.addRule()
                it.addRow("AVB info", Avb.getJsonFileName(info.output))
            }
            //kernel
            it.addRule()
            it.addRow("kernel", this.kernel.file)
            File(Helper.prop("kernelVersionFile")).let { kernelVersionFile ->
                if (kernelVersionFile.exists()) {
                    it.addRow("\\-- version " + kernelVersionFile.readLines().toString(), kernelVersionFile.path)
                }
            }
            File(Helper.prop("kernelConfigFile")).let { kernelConfigFile ->
                if (kernelConfigFile.exists()) {
                    it.addRow("\\-- config", kernelConfigFile.path)
                }
            }
            //ramdisk
            if (this.ramdisk.size > 0) {
                it.addRule()
                it.addRow("ramdisk", this.ramdisk.file)
                it.addRow("\\-- extracted ramdisk rootfs", "${workDir}root")
            }
            //second
            this.secondBootloader?.let { theSecondBootloader ->
                if (theSecondBootloader.size > 0) {
                    it.addRule()
                    it.addRow("second bootloader", theSecondBootloader.file)
                }
            }
            //dtbo
            this.recoveryDtbo?.let { theDtbo ->
                if (theDtbo.size > 0U) {
                    it.addRule()
                    it.addRow("recovery dtbo", theDtbo.file)
                }
            }
            //dtb
            this.dtb?.let { theDtb ->
                if (theDtb.size > 0u) {
                    it.addRule()
                    it.addRow("dtb", theDtb.file)
                    if (File(theDtb.file + ".src").exists()) {
                        it.addRow("\\-- decompiled dts", theDtb.file + ".src")
                    }
                }
            }
            //END
            it.addRule()
            it
        }
        val tabVBMeta = AsciiTable().let {
            if (File("vbmeta.img").exists()) {
                it.addRule()
                it.addRow("vbmeta.img", Avb.getJsonFileName("vbmeta.img"))
                it.addRule()
                "\n" + it.render()
            } else {
                ""
            }
        }
        log.info("\n\t\t\tUnpack Summary of ${info.output}\n{}\n{}{}",
                tableHeader.render(), tab.render(), tabVBMeta)
        return this
    }

    private fun toHeader(): BootHeaderV2 {
        return BootHeaderV2(
                kernelLength = kernel.size.toUInt(),
                kernelOffset = kernel.loadOffset,
                ramdiskLength = ramdisk.size.toUInt(),
                ramdiskOffset = ramdisk.loadOffset,
                secondBootloaderLength = if (secondBootloader != null) secondBootloader!!.size.toUInt() else 0U,
                secondBootloaderOffset = if (secondBootloader != null) secondBootloader!!.loadOffset else 0U,
                recoveryDtboLength = if (recoveryDtbo != null) recoveryDtbo!!.size.toUInt() else 0U,
                recoveryDtboOffset = if (recoveryDtbo != null) recoveryDtbo!!.loadOffset else 0U,
                dtbLength = if (dtb != null) dtb!!.size else 0U,
                dtbOffset = if (dtb != null) dtb!!.loadOffset else 0U,
                tagsOffset = info.tagsOffset,
                pageSize = info.pageSize,
                headerSize = info.headerSize,
                headerVersion = info.headerVersion,
                board = info.board.toString(),
                cmdline = info.cmdline,
                hash = info.hash,
                osVersion = info.osVersion,
                osPatchLevel = info.osPatchLevel
        )
    }

    fun pack(): BootV2 {
        //refresh kernel size
        this.kernel.size = File(this.kernel.file!!).length().toInt()
        //refresh ramdisk size
        if (this.ramdisk.file.isNullOrBlank()) {
            ramdisk.file = null
            ramdisk.loadOffset = 0U
        } else {
            if (File(this.ramdisk.file!!).exists() && !File(workDir + "root").exists()) {
                //do nothing if we have ramdisk.img.gz but no /root
                log.warn("Use prebuilt ramdisk file: ${this.ramdisk.file}")
            } else {
                File(this.ramdisk.file!!).deleleIfExists()
                File(this.ramdisk.file!!.removeSuffix(".gz")).deleleIfExists()
                Common.packRootfs("${workDir}/root", this.ramdisk.file!!)
            }
            this.ramdisk.size = File(this.ramdisk.file!!).length().toInt()
        }
        //refresh second bootloader size
        secondBootloader?.let { theSecond ->
            theSecond.size = File(theSecond.file!!).length().toInt()
        }
        //refresh recovery dtbo size
        recoveryDtbo?.let { theDtbo ->
            theDtbo.size = File(theDtbo.file!!).length().toUInt()
            theDtbo.loadOffset = getRecoveryDtboPosition().toULong()
            log.warn("using fake recoveryDtboOffset ${theDtbo.loadOffset} (as is in AOSP avbtool)")
        }
        //refresh dtb size
        dtb?.let { theDtb ->
            theDtb.size = File(theDtb.file!!).length().toUInt()
        }
        //refresh image hash
        info.hash = when (info.headerVersion) {
            0U -> {
                Common.hashFileAndSize(kernel.file, ramdisk.file, secondBootloader?.file)
            }
            1U -> {
                Common.hashFileAndSize(kernel.file, ramdisk.file,
                        secondBootloader?.file, recoveryDtbo?.file)
            }
            2U -> {
                Common.hashFileAndSize(kernel.file, ramdisk.file,
                        secondBootloader?.file, recoveryDtbo?.file, dtb?.file)
            }
            else -> {
                throw IllegalArgumentException("headerVersion ${info.headerVersion} illegal")
            }
        }

        val encodedHeader = this.toHeader().encode()

        //write
        FileOutputStream("${info.output}.clear", false).use { fos ->
            fos.write(encodedHeader)
            fos.write(ByteArray((Helper.round_to_multiple(encodedHeader.size.toUInt(), info.pageSize) - encodedHeader.size.toUInt()).toInt()))
        }

        log.info("Writing data ...")
        val bytesV2 = ByteBuffer.allocate(1024 * 1024 * 64)//assume total SIZE small than 64MB
                .let { bf ->
                    bf.order(ByteOrder.LITTLE_ENDIAN)
                    Common.writePaddedFile(bf, kernel.file!!, info.pageSize)
                    if (ramdisk.size > 0) {
                        Common.writePaddedFile(bf, ramdisk.file!!, info.pageSize)
                    }
                    secondBootloader?.let {
                        Common.writePaddedFile(bf, secondBootloader!!.file!!, info.pageSize)
                    }
                    recoveryDtbo?.let {
                        Common.writePaddedFile(bf, recoveryDtbo!!.file!!, info.pageSize)
                    }
                    dtb?.let {
                        Common.writePaddedFile(bf, dtb!!.file!!, info.pageSize)
                    }
                    bf
                }
        //write
        FileOutputStream("${info.output}.clear", true).use { fos ->
            fos.write(bytesV2.array(), 0, bytesV2.position())
        }

        this.toCommandLine().apply {
            addArgument("${info.output}.google")
            log.info(this.toString())
            DefaultExecutor().execute(this)
        }

        Common.assertFileEquals("${info.output}.clear", "${info.output}.google")

        return this
    }

    private fun toCommandLine(): CommandLine {
        val ret = CommandLine(Helper.prop("mkbootimg"))
        ret.addArgument(" --header_version ")
        ret.addArgument(info.headerVersion.toString())
        ret.addArgument(" --base ")
        ret.addArgument("0x" + java.lang.Long.toHexString(0))
        ret.addArgument(" --kernel ")
        ret.addArgument(kernel.file!!)
        ret.addArgument(" --kernel_offset ")
        ret.addArgument("0x" + Integer.toHexString(kernel.loadOffset.toInt()))
        if (this.ramdisk.size > 0) {
            ret.addArgument(" --ramdisk ")
            ret.addArgument(ramdisk.file)
        }
        ret.addArgument(" --ramdisk_offset ")
        ret.addArgument("0x" + Integer.toHexString(ramdisk.loadOffset.toInt()))
        if (secondBootloader != null) {
            ret.addArgument(" --second ")
            ret.addArgument(secondBootloader!!.file!!)
            ret.addArgument(" --second_offset ")
            ret.addArgument("0x" + Integer.toHexString(secondBootloader!!.loadOffset.toInt()))
        }
        if (!info.board.isNullOrBlank()) {
            ret.addArgument(" --board ")
            ret.addArgument(info.board)
        }
        if (info.headerVersion > 0U) {
            if (recoveryDtbo != null) {
                ret.addArgument(" --recovery_dtbo ")
                ret.addArgument(recoveryDtbo!!.file!!)
            }
        }
        if (info.headerVersion > 1U) {
            if (dtb != null) {
                ret.addArgument("--dtb ")
                ret.addArgument(dtb!!.file!!)
                ret.addArgument("--dtb_offset ")
                ret.addArgument("0x" + java.lang.Long.toHexString(dtb!!.loadOffset.toLong()))
            }
        }
        ret.addArgument(" --pagesize ")
        ret.addArgument(Integer.toString(info.pageSize.toInt()))
        ret.addArgument(" --cmdline ")
        ret.addArgument(info.cmdline, false)
        if (!info.osVersion.isNullOrBlank()) {
            ret.addArgument(" --os_version ")
            ret.addArgument(info.osVersion)
        }
        if (!info.osPatchLevel.isNullOrBlank()) {
            ret.addArgument(" --os_patch_level ")
            ret.addArgument(info.osPatchLevel)
        }
        ret.addArgument(" --tags_offset ")
        ret.addArgument("0x" + Integer.toHexString(info.tagsOffset.toInt()))
        ret.addArgument(" --id ")
        ret.addArgument(" --output ")
        //ret.addArgument("boot.img" + ".google")

        log.debug("To Commandline: $ret")

        return ret
    }

    fun sign(): BootV2 {
        if (info.verify == "VB2.0") {
            Signer.signAVB(info.output, this.info.imageSize)
            log.info("Adding hash_footer with verified-boot 2.0 style")
        } else {
            Signer.signVB1(info.output + ".clear", info.output + ".signed")
        }
        return this
    }
}
