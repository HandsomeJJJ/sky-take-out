package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.PasswordConstant;
import com.sky.constant.StatusConstant;
import com.sky.context.BaseContext;
import com.sky.dto.EmployeeDTO;
import com.sky.dto.EmployeeLoginDTO;
import com.sky.dto.EmployeePageQueryDTO;
import com.sky.entity.Employee;
import com.sky.exception.AccountLockedException;
import com.sky.exception.AccountNotFoundException;
import com.sky.exception.BaseException;
import com.sky.exception.PasswordErrorException;
import com.sky.mapper.EmployeeMapper;
import com.sky.properties.JwtProperties;
import com.sky.result.PageResult;
import com.sky.security.LoginAttemptGuard;
import com.sky.security.TokenBlacklist;
import com.sky.service.EmployeeService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.sky.utils.PasswordHasher;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class EmployeeServiceImpl implements EmployeeService {

    @Autowired
    private EmployeeMapper employeeMapper;
    @Autowired
    private LoginAttemptGuard loginAttemptGuard;
    @Autowired
    private TokenBlacklist tokenBlacklist;
    @Autowired
    private JwtProperties jwtProperties;

    /**
     * 员工登录
     *
     * @param employeeLoginDTO
     * @return
     */
    public Employee login(EmployeeLoginDTO employeeLoginDTO) {
        String username = employeeLoginDTO.getUsername();
        String password = employeeLoginDTO.getPassword();

        // 连续失败达到阈值后先拦掉，不给暴力破解留无限次尝试的机会
        if (loginAttemptGuard.isLocked(username)) {
            throw new AccountLockedException(MessageConstant.ACCOUNT_LOCKED);
        }

        //1、根据用户名查询数据库中的数据
        Employee employee = employeeMapper.getByUsername(username);

        //2、处理各种异常情况（用户名不存在、密码不对、账号被锁定）
        if (employee == null) {
            //账号不存在。这里不计数：账号不存在也计的话，攻击者可以用"真实用户名+错密码"
            //把别人的账号锁死，防破解的机制本身就成了攻击面。账号探测由接口级 IP 限流兜。
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }

        //密码比对：库里存的是哈希，用哈希算法自带的 matches 比较，不做反向解密
        if (!PasswordHasher.matches(password, employee.getPassword())) {
            //密码错误
            if (loginAttemptGuard.recordFailure(username)) {
                throw new AccountLockedException(MessageConstant.ACCOUNT_LOCKED);
            }
            throw new PasswordErrorException(MessageConstant.PASSWORD_ERROR);
        }

        // 老数据仍是无盐 MD5：本次登录校验已通过，就地重写成 BCrypt，避免把已有账号锁在门外
        if (PasswordHasher.isLegacyMd5(employee.getPassword())) {
            employeeMapper.updatePasswordById(employee.getId(), PasswordHasher.hash(password));
            log.info("员工 {} 的密码已从 MD5 就地升级为 BCrypt", employee.getUsername());
        }

        if (StatusConstant.DISABLE.equals(employee.getStatus())) {
            //账号被锁定
            throw new AccountLockedException(MessageConstant.ACCOUNT_LOCKED);
        }

        // 登录成功清零计数，避免正常使用被累积到阈值
        loginAttemptGuard.clear(username);

        //3、返回实体对象
        return employee;
    }

    /**
     * 新增员工
     *
     * @param employeeDTO
     */
    public void save(EmployeeDTO employeeDTO) {
        Employee employee = new Employee();

        //对象属性拷贝
        BeanUtils.copyProperties(employeeDTO, employee);

        //设置账号的状态，默认正常状态 1表示正常 0表示锁定
        employee.setStatus(StatusConstant.ENABLE);

        //设置密码，默认密码123456
        employee.setPassword(PasswordHasher.hash(PasswordConstant.DEFAULT_PASSWORD));

        //设置当前记录的创建时间和修改时间
        //employee.setCreateTime(LocalDateTime.now());
        //employee.setUpdateTime(LocalDateTime.now());

        //设置当前记录创建人id和修改人id
        //employee.setCreateUser(BaseContext.getCurrentId());
        //employee.setUpdateUser(BaseContext.getCurrentId());

        employeeMapper.insert(employee);
    }

    /**
     * 分页查询
     *
     * @param employeePageQueryDTO
     * @return
     */
    public PageResult pageQuery(EmployeePageQueryDTO employeePageQueryDTO) {
        // select * from employee limit 0,10
        //开始分页查询
        int pageNum = employeePageQueryDTO.getPage() < 1 ? 1 : employeePageQueryDTO.getPage();
        int limit = Math.min(Math.max(employeePageQueryDTO.getPageSize(), 1), 100);
        PageHelper.startPage(pageNum, limit);

        Page<Employee> page = employeeMapper.pageQuery(employeePageQueryDTO);

        long total = page.getTotal();
        List<Employee> records = page.getResult();

        return new PageResult(total, records);
    }

    /**
     * 启用禁用员工账号
     *
     * @param status
     * @param id
     */
    public void startOrStop(Integer status, Long id) {
        if (!StatusConstant.ENABLE.equals(status) && !StatusConstant.DISABLE.equals(status)) {
            throw new BaseException("账号状态只能是 0（禁用）或 1（启用）");
        }
        if (id == null) {
            throw new BaseException("缺少员工 id");
        }
        // 原来可以把自己禁用掉：点完之后当前会话立刻不可用，而系统里可能已经没有第二个能启用它的账号了
        if (StatusConstant.DISABLE.equals(status) && id.equals(BaseContext.getCurrentId())) {
            throw new BaseException(MessageConstant.CANNOT_DISABLE_SELF);
        }

        Employee employee = Employee.builder()
                .status(status)
                .id(id)
                .build();

        employeeMapper.update(employee);

        // 禁用要立刻掐掉对方已签发的令牌：JWT 在服务端删不掉，而这里拿不到对方的令牌原文，
        // 所以按员工记一个"作废时间点"，早于它签发的令牌在拦截器里一律拒绝（不用等 TTL 到期）。
        if (StatusConstant.DISABLE.equals(status)) {
            tokenBlacklist.revokeEmployee(id, jwtProperties.getAdminTtl());
            log.info("员工 {} 已禁用，其此前签发的令牌同时作废", id);
        }
    }

    /**
     * 根据id查询员工
     *
     * @param id
     * @return
     */
    public Employee getById(Long id) {
        Employee employee = employeeMapper.getById(id);
        if (employee == null) {
            throw new BaseException("员工不存在");
        }
        employee.setPassword("****");
        return employee;
    }

    /**
     * 编辑员工信息
     *
     * @param employeeDTO
     */
    public void update(EmployeeDTO employeeDTO) {
        Employee employee = new Employee();
        BeanUtils.copyProperties(employeeDTO, employee);
        // 账号名不允许通过编辑接口改写，否则等于可以把管理员账号改名后接管
        employee.setUsername(null);

        //employee.setUpdateTime(LocalDateTime.now());
        //employee.setUpdateUser(BaseContext.getCurrentId());

        employeeMapper.update(employee);
    }
}
