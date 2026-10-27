package com.sky.controller.admin;

import com.sky.constant.MessageConstant;
import com.sky.result.Result;
import com.sky.utils.AliOssUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 通用接口
 */
@RestController
@RequestMapping("/admin/common")
@Api(tags = "通用接口")
@Slf4j
public class CommonController {

    /** 允许的图片后缀 */
    private static final List<String> ALLOWED_EXTENSIONS = List.of("jpg", "jpeg", "png", "gif", "webp");

    private final AliOssUtil aliOssUtil;
    private final long maxUploadBytes;

    public CommonController(AliOssUtil aliOssUtil,
                            @Value("${sky.upload.max-size-kb:2048}") long maxUploadKb) {
        this.aliOssUtil = aliOssUtil;
        this.maxUploadBytes = maxUploadKb * 1024;
    }

    /**
     * 文件上传
     */
    @PostMapping("/upload")
    @ApiOperation("文件上传")
    public Result<String> upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Result.error("请选择要上传的文件");
        }

        String originalFilename = file.getOriginalFilename();
        String extension = extensionOf(originalFilename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            log.warn("拒绝上传非白名单文件类型，filename={}, extension={}", originalFilename, extension);
            return Result.error("只允许上传 jpg/jpeg/png/gif/webp 图片");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("读取上传文件失败", e);
            return Result.error(MessageConstant.UPLOAD_FAILED);
        }

        if (bytes.length > maxUploadBytes) {
            return Result.error("文件超过 " + (maxUploadBytes / 1024) + "KB 上限");
        }
        // 后缀可以随便编，文件头不能：按真实内容再判一次类型
        if (!isImageMagic(bytes)) {
            log.warn("文件内容与图片不符，filename={}, head={}", originalFilename, headHex(bytes));
            return Result.error("文件内容与图片格式不符");
        }

        // 对象名一律用服务端生成的 UUID + 白名单后缀，不使用客户端提供的文件名，
        // 否则 ../ 或超长中文名会进到 OSS key 里
        String objectName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        String filePath = aliOssUtil.upload(bytes, objectName);
        log.info("文件上传成功，objectName={}", objectName);
        return Result.success(filePath);
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isImageMagic(byte[] b) {
        if (b == null || b.length < 12) {
            return false;
        }
        if (b[0] == (byte) 0xFF && b[1] == (byte) 0xD8 && b[2] == (byte) 0xFF) {
            return true; // JPEG
        }
        if (b[0] == (byte) 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return true; // PNG
        }
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return true; // GIF
        }
        // WEBP: "RIFF" .... "WEBP"
        return b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    private static String headHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(4, b.length); i++) {
            sb.append(String.format("%02x", b[i]));
        }
        return sb.toString();
    }
}
