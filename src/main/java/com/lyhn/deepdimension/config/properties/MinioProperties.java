package com.lyhn.deepdimension.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {
    /** minio 服务端地址 */
    private String endpoint;
    /** minio 访问密钥 */
    private String accessKey;
    /** minio 密钥 */
    private String secretKey;
    /** minio 存储桶名称 */
    private String bucketName;

}
