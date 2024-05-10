package com.wuyr.agent_injector

import android.annotation.SuppressLint
import android.os.Build
import android.os.Process
import com.wuyr.agent_injector.adb.AdbClient
import com.wuyr.agent_injector.exception.AgentAttachException
import com.wuyr.agent_injector.exception.ProcessNotFoundException
import com.wuyr.agent_injector.exception.RootNotDetectedException
import com.wuyr.agent_injector.exception.UidSwitchingException
import java.io.File

/**
 * @author wuyr
 * @github https://github.com/wuyr/agent-injector-for-android
 * @since 2024-04-30 下午4:53
 */
object AgentInjector {

    val AdbClient.deviceRooted: Boolean
        get() = sendShellCommand(System.getenv("PATH")?.split(File.pathSeparatorChar)
            ?.joinToString(" || ") { "ls $it/su 2>/dev/null" } ?: "").split("\n").getOrNull(1)?.contains("/su") ?: false

    /**
     * 向目标进程注入代码
     *
     * [adbHost] adb ip
     * [adbPort] adb 端口
     * [packageName] 本应用包名
     * [useRoot] 是否使用Root权限（对于系统app和release版apk，必须使用Root）
     * [targetPackageName] 目标应用包名（要注入的apk包名）
     * [targetProcessName] 目标应用进程名（如果没有在Manifest里指定，默认就是apk包名）
     * [dexPath] apk或dex文件的路径
     * [mainClassName] 注入成功后将要调用的类名（必须在[dexPath]里存在）
     * [mainMethodName] 将要调用的静态无参方法名（必须在[mainClassName]里存在。默认是main，对应public static void main() {} ）
     */
    @JvmStatic
    fun inject(
        adbHost: String, adbPort: Int, packageName: String, useRoot: Boolean, targetPackageName: String,
        targetProcessName: String, dexPath: String, mainClassName: String, mainMethodName: String = "main",
    ) {
        AdbClient.openShell(adbHost, adbPort).use { adb ->
            var is64bit = false
            val targetPid = runCatching {
                val zygote64Pid = adb.sendShellCommand("ps -P 1 -o PID,NAME | grep -w zygote64 | awk 'NR==1{print \$1}'").split("\n").getOrNull(1)?.trim()?.toInt() ?: 0
                // 根据进程名查找对应的pid
                val (pid, parentPid) = adb.sendShellCommand("ps -A -o PID,PPID,NAME | grep -w $targetProcessName | awk 'NR==1{print \$1,\$2}'")
                    .split("\n").getOrNull(1)?.trim()?.split("\\s+".toRegex())?.let {
                        (it.getOrNull(0)?.trim()?.toInt() ?: 0) to (it.getOrNull(1)?.trim()?.toInt() ?: 0)
                    } ?: (0 to 0)
                is64bit = parentPid == zygote64Pid
                pid
            }.getOrDefault(0)
            if (targetPid == 0) {
                // 进程未运行
                throw ProcessNotFoundException()
            }
            // 先找到本应用apk路径
            val apkPath = adb.sendShellCommand("pm path $packageName").split("\n").find { it.trim().startsWith("package:") }?.substring(8)?.trim() ?: ""
            if (apkPath.isEmpty() || !apkPath.endsWith("apk")) {
                throw IllegalArgumentException("package not found: $packageName")
            }
            @SuppressLint("SdCardPath")
            val targetDataDir = "/data/user/${Process.myUid() / 100000}/$targetPackageName"
            // 如果使用root权限，则使用su+cd命令，否则使用run-as命令来部署agent
            if (useRoot) {
                if (adb.deviceRooted) {
                    var response = adb.sendShellCommand("su").split("\n").getOrNull(1)?.trim() ?: ""
                    // 检查身份是否切换成功
                    if (adb.sendShellCommand("id").contains("uid=0(")) {
                        adb.sendShellCommand("cd $targetDataDir")
                        response = adb.sendShellCommand("pwd").split("\n").getOrNull(1)?.trim() ?: ""
                        if (!response.contains(targetPackageName)) {
                            throw RuntimeException(response)
                        }
                    } else {
                        throw UidSwitchingException(response)
                    }
                } else {
                    throw RootNotDetectedException()
                }
            } else {
                val response = adb.sendShellCommand("run-as $targetPackageName").split("\n").getOrNull(1)?.trim() ?: ""
                // 检查身份是否切换成功
                if (adb.sendShellCommand("id").contains("uid=2000(")) {
                    throw UidSwitchingException(response)
                }
            }
            val libraryName = "libagent-injector.so"
            val injectorFolder = "agent-injector"
            // 创建目录
            adb.sendShellCommand("rm -rf $injectorFolder;mkdir $injectorFolder")
            // 复制可执行文件
            adb.sendShellCommand("cp $dexPath $injectorFolder/drug && chmod 444 $injectorFolder/drug")
            // 选择合适架构的so
            val libs = adb.sendShellCommand("unzip -l $apkPath lib/*/$libraryName")
            val supportedAbiList = if (is64bit) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
            val targetAbi = supportedAbiList.find { abi -> libs.contains("lib/$abi/$libraryName") }
                ?: throw UnsupportedOperationException("unsupported this device, abi list: (${supportedAbiList.joinToString(", ")})")
            // 解压本应用apk的libagent-injector.so到目标进程沙盒目录
            adb.sendShellCommand("unzip -q -o -d $injectorFolder $apkPath lib/$targetAbi/$libraryName")
            // 检查so是否复制成功
            val libraryPath = adb.sendShellCommand("ls $injectorFolder/lib/$targetAbi/$libraryName").split("\n").getOrNull(1)?.trim() ?: ""
            if (libraryPath.isEmpty() || !libraryPath.endsWith("so")) {
                throw RuntimeException("failed to copy agent")
            }
            // 创建class-info文件并写入类名和方法名
            adb.sendShellCommand("echo \"$mainClassName\n$mainMethodName\" > $injectorFolder/class-info;exit")
            // 开始attach agent
            val response = adb.sendShellCommand("am attach-agent $targetProcessName $targetDataDir/$libraryPath && echo \"agent attached\"").split("\n").getOrNull(1)?.trim() ?: ""
            if (!response.contains("agent attached")) {
                // 自己编译测试时如果报这个错，多半是so架构不兼容，比如当前要注入的apk是32bit，但运行了64bit的so
                // 这是因为从as直接run，as会主动裁剪lib，把32bit的so给排除了，没有打包进apk，遇到这种情况，可以先build apk，然后通过adb install xxx.apk来安装即可
                throw AgentAttachException()
            }
        }
    }
}