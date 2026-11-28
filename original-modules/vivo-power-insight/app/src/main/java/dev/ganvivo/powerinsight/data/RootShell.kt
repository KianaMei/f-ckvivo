package dev.ganvivo.powerinsight.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class RootExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0
}

object RootShell {
    suspend fun checkRoot(): Boolean = withContext(Dispatchers.IO) {
        val res = runScript("id")
        res.isSuccess && res.stdout.contains("uid=0")
    }

    /**
     * 通过单一 su 进程执行只读脚本（标准输入传入），不向设备磁盘写入任何临时脚本或文件。
     */
    suspend fun runScript(script: String, timeoutSec: Long = 25L): RootExecResult =
        withContext(Dispatchers.IO) {
            try {
                val proc = ProcessBuilder("su", "-c", "sh -s")
                    .redirectErrorStream(false)
                    .start()

                OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(script)
                    writer.write("\nexit\n")
                    writer.flush()
                }

                val stdoutBuilder = StringBuilder(65536)
                val stderrBuilder = StringBuilder(4096)

                val outThread = Thread {
                    BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)).use { br ->
                        val buf = CharArray(8192)
                        while (true) {
                            val n = br.read(buf)
                            if (n <= 0) break
                            stdoutBuilder.append(buf, 0, n)
                        }
                    }
                }
                val errThread = Thread {
                    BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8)).use { br ->
                        val buf = CharArray(2048)
                        while (true) {
                            val n = br.read(buf)
                            if (n <= 0) break
                            stderrBuilder.append(buf, 0, n)
                        }
                    }
                }
                outThread.start()
                errThread.start()

                val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    return@withContext RootExecResult(-1, stdoutBuilder.toString(), "Root 命令执行超时（${timeoutSec}s）")
                }
                outThread.join(2000)
                errThread.join(2000)

                RootExecResult(proc.exitValue(), stdoutBuilder.toString(), stderrBuilder.toString())
            } catch (e: Exception) {
                RootExecResult(-1, "", e.message ?: "无法拉起 su 进程，请检查 Root 授权")
            }
        }
}
