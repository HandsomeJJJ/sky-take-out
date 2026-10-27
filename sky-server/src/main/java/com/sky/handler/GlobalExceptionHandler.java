package com.sky.handler;

import com.sky.constant.MessageConstant;
import com.sky.exception.BaseException;
import com.sky.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import java.util.stream.Collectors;

/**
 * 全局异常处理器，处理项目中抛出的业务异常
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 捕获业务异常
     * @param ex
     * @return
     */
    @ExceptionHandler
    public Result exceptionHandler(BaseException ex){
        log.error("异常信息：{}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    /**
     * 唯一索引冲突。MyBatis-Spring 会把 JDBC 的
     * SQLIntegrityConstraintViolationException 翻译成 DuplicateKeyException，
     * 所以要接的是后者，接前者永远命中不了。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result exceptionHandler(DuplicateKeyException ex){
        String message = ex.getMessage();
        if (message != null && message.contains("Duplicate entry")) {
            log.info("唯一约束冲突：{}", message);
            return Result.error("已存在相同的记录");
        }
        log.error("数据库写入失败", ex);
        return Result.error(MessageConstant.UNKNOWN_ERROR);
    }

    /**
     * @Valid 校验 @RequestBody 失败。
     * 这类是"请求本身不合法"，返回 400 而不是 200 + code=0，
     * 前端需要按 4xx 分支展示校验信息。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result exceptionHandler(MethodArgumentNotValidException ex){
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败：{}", msg);
        return Result.error(msg.isEmpty() ? "请求参数不合法" : msg);
    }

    /**
     * 方法参数（query param / path variable）上的约束校验失败，
     * 需要 Controller 类上有 @Validated 才走这里。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result exceptionHandler(ConstraintViolationException ex){
        String msg = ex.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败：{}", msg);
        return Result.error(msg.isEmpty() ? "请求参数不合法" : msg);
    }

    /**
     * 参数语义不合法，比如统计区间开始日期晚于结束日期
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result exceptionHandler(IllegalArgumentException ex){
        log.warn("请求参数不合法：{}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    /**
     * 类型不匹配，比如把 id 传成 "abc"
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result exceptionHandler(MethodArgumentTypeMismatchException ex){
        log.warn("参数类型错误：{}={}", ex.getName(), ex.getValue());
        return Result.error("参数 " + ex.getName() + " 格式不正确");
    }

    /**
     * 兜底：没有兜底 handler 时，未捕获异常会直接暴露成 500 错误页/堆栈。
     * 这里统一转成 Result，并保留完整堆栈到日志。
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result exceptionHandler(Exception ex){
        log.error("未处理异常：", ex);
        return Result.error(MessageConstant.UNKNOWN_ERROR);
    }
}
