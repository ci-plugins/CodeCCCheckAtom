package com.tencent.devops.docker

import com.tencent.bk.devops.atom.common.Status
import com.tencent.bk.devops.plugin.executor.docker.DockerApi
import com.tencent.bk.devops.plugin.pojo.docker.DockerRunLogRequest
import com.tencent.bk.devops.plugin.pojo.docker.DockerRunLogResponse
import com.tencent.bk.devops.plugin.pojo.docker.DockerRunRequest
import com.tencent.bk.devops.plugin.pojo.docker.common.DockerStatus
import com.tencent.devops.docker.pojo.CommandParam
import com.tencent.devops.docker.pojo.ImageParam
import com.tencent.devops.docker.tools.LogUtils
import com.tencent.devops.docker.utils.CodeccConfig
import com.tencent.devops.pojo.exception.CodeccTaskExecException
import org.apache.commons.lang3.StringUtils
import java.io.File

object DockerRun {
    val api = DockerApi()

    fun runImage(imageParam: ImageParam, commandParam: CommandParam, toolName: String) {
        LogUtils.printDebugLog("execute image params: $imageParam")

        val param = DockerRunRequest(
            userId = commandParam.landunParam.userId,
            imageName = imageParam.imageName,
            command = imageParam.command,
            dockerLoginUsername = imageParam.registryUser,
            dockerLoginPassword = imageParam.registryPwd,
            workspace = File(commandParam.landunParam.streamCodePath),
            extraOptions = imageParam.env.plus(mapOf(
                "devCloudAppId" to commandParam.devCloudAppId,
                "devCloudUrl" to commandParam.devCloudUrl,
                "devCloudToken" to commandParam.devCloudToken
            ))

        )
        val dockerRunResponse = api.dockerRunCommand(
            projectId = commandParam.landunParam.devopsProjectId,
            pipelineId = commandParam.landunParam.devopsPipelineId,
            buildId = commandParam.landunParam.buildId,
            param = param
        ).data!!

        var extraOptions = dockerRunResponse.extraOptions
        val channelCode = CodeccConfig.getConfig("LANDUN_CHANNEL_CODE")

        val isGongFengScan = channelCode == "GONGFENGSCAN" || commandParam.extraPrams["BK_CODECC_SCAN_MODE"] == "GONGFENGSCAN"

        val timeGap = if (isGongFengScan) 30 * 1000L else 5000L
        for (i in 1..100000000) {
            Thread.sleep(timeGap)

            val runLogResponse = getRunLogResponse(api, commandParam, extraOptions, timeGap)

            extraOptions = runLogResponse.extraOptions

            var isBlank = false
            runLogResponse.log?.forEachIndexed { index, s ->
                if (StringUtils.isBlank(s)) {
                    isBlank = true
                    LogUtils.printStr(".")
                } else {
                    if (isBlank) {
                        isBlank = false
                        LogUtils.printLog("")
                    }
                    LogUtils.printLog("[docker]: $s")
                }
            }

            when (runLogResponse.status) {
                DockerStatus.success -> {
                    LogUtils.printLog("docker run success: $runLogResponse")
                    return
                }
                DockerStatus.failure -> {
                    throw CodeccTaskExecException(errorMsg = "docker run fail: $runLogResponse", toolName = toolName)
                }
                else -> {
                    if (i % 16 == 0) LogUtils.printLog("docker run status: $runLogResponse")
                }
            }
        }
    }

    private fun getRunLogResponse(api: DockerApi, commandParam: CommandParam, extraOptions: Map<String, String>, timeGap: Long): DockerRunLogResponse {
        try {
            return api.dockerRunGetLog(
                projectId = commandParam.landunParam.devopsProjectId,
                pipelineId = commandParam.landunParam.devopsPipelineId,
                buildId = commandParam.landunParam.buildId,
                param = DockerRunLogRequest(
                    userId = commandParam.landunParam.userId,
                    workspace = File(commandParam.landunParam.streamCodePath),
                    timeGap = timeGap,
                    extraOptions = extraOptions
                )
            ).data!!
        } catch (e: Exception) {
            LogUtils.printErrorLog("fail to get docker run log: ${commandParam.landunParam.buildId}, " +
                "${commandParam.landunParam.devopsVmSeqId}, " +
                extraOptions
            )
            e.printStackTrace()
        }
        return DockerRunLogResponse(
            log = listOf(),
            status = DockerStatus.running,
            message = "",
            extraOptions = extraOptions
        )
    }
}
