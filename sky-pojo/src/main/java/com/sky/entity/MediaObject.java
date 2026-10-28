package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 对象存储里一个文件的登记行。
 * <p>
 * 存在的理由：文件被业务表引用着，删菜品不等于可以删对象 —— 历史订单明细里还存着同一个 URL，
 * 立刻删会让用户自己的订单详情变成 404 图。所以需要一个"何时可以真的删"的状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaObject implements Serializable {

    /** 仍有业务表在用 */
    public static final int ACTIVE = 0;
    /** 已无引用，等待保留期结束 */
    public static final int RETIRING = 1;
    /** 已从对象存储删除 */
    public static final int DELETED = 2;

    private static final long serialVersionUID = 1L;

    private Long id;

    /** bucket 内的对象名 */
    private String objectName;

    /** 完整访问地址，业务表 image 列存的就是它 */
    private String url;

    private Integer status;

    /** 进入待回收的时间，物理删除按它加保留期判定 */
    private LocalDateTime retireTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
