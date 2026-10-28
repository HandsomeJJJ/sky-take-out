package com.sky.utils;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.sky.exception.BaseException;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import java.io.ByteArrayInputStream;

@Data
@AllArgsConstructor
@Slf4j
public class AliOssUtil {

    private String endpoint;
    private String accessKeyId;
    private String accessKeySecret;
    private String bucketName;

    /**
     * 文件上传
     *
     * @param bytes
     * @param objectName
     * @return
     */
    public String upload(byte[] bytes, String objectName) {

        // 创建OSSClient实例。
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);

        try {
            // 创建PutObject请求。
            ossClient.putObject(bucketName, objectName, new ByteArrayInputStream(bytes));
        } catch (OSSException oe) {
            // 原来只往 stdout 打一行然后继续往下走，最后照样返回 URL ——
            // 调用方以为上传成功了，实际对象并不存在，前端拿到的是 404 图片
            log.error("OSS 上传被拒绝，errorMessage={}, errorCode={}, requestId={}",
                    oe.getErrorMessage(), oe.getErrorCode(), oe.getRequestId());
            throw new BaseException("文件上传失败：" + oe.getErrorCode());
        } catch (ClientException ce) {
            log.error("OSS 客户端异常，errorMessage={}", ce.getMessage());
            throw new BaseException("文件上传失败：无法连接对象存储");
        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }

        //文件访问路径规则 https://BucketName.Endpoint/ObjectName
        StringBuilder stringBuilder = new StringBuilder("https://");
        stringBuilder
                .append(bucketName)
                .append(".")
                .append(endpoint)
                .append("/")
                .append(objectName);

        log.info("文件上传到:{}", stringBuilder.toString());

        return stringBuilder.toString();
    }

    /**
     * 删除对象存储上的文件。
     * <p>
     * 原来只有 upload，没有任何一处删除：删菜品、换菜品图片之后，旧图永久留在 bucket 里。
     * 单个文件不大，但这是一个只增不减的存储成本，而且里面装着下架商品的图片。
     *
     * @param objectName 对象名（bucket 内的 key，不是完整 URL）
     */
    public void delete(String objectName) {
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        try {
            ossClient.deleteObject(bucketName, objectName);
            log.info("OSS 对象已删除：{}", objectName);
        } catch (OSSException oe) {
            log.error("OSS 删除被拒绝，objectName={}, errorCode={}, requestId={}",
                    objectName, oe.getErrorCode(), oe.getRequestId());
            throw new BaseException("文件删除失败：" + oe.getErrorCode());
        } catch (ClientException ce) {
            log.error("OSS 客户端异常，objectName={}, errorMessage={}", objectName, ce.getMessage());
            throw new BaseException("文件删除失败：无法连接对象存储");
        } finally {
            ossClient.shutdown();
        }
    }
}
