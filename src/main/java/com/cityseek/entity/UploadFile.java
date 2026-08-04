package com.cityseek.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("tb_upload_file")
public class UploadFile {

    public static final int TEMPORARY = 0;
    public static final int BOUND = 1;
    public static final int DELETED = 2;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String path;
    private Long uploaderId;
    private String businessType;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
