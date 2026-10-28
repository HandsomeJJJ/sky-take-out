package com.sky.service;

import com.sky.constant.StatusConstant;
import com.sky.context.BaseContext;
import com.sky.dto.EmployeeLoginDTO;
import com.sky.entity.Employee;
import com.sky.exception.AccountLockedException;
import com.sky.exception.AccountNotFoundException;
import com.sky.exception.BaseException;
import com.sky.exception.PasswordErrorException;
import com.sky.mapper.EmployeeMapper;
import com.sky.properties.JwtProperties;
import com.sky.security.LoginAttemptGuard;
import com.sky.security.TokenBlacklist;
import com.sky.service.impl.EmployeeServiceImpl;
import com.sky.utils.PasswordHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账号安全：禁用不能把自己关在门外，禁用要立刻失效，密码错误要能被限速。
 */
@ExtendWith(MockitoExtension.class)
class EmployeeServiceImplTest {

    private static final Long CURRENT_ID = 1L;
    private static final String USERNAME = "admin";

    @Mock
    private EmployeeMapper employeeMapper;
    @Mock
    private LoginAttemptGuard loginAttemptGuard;
    @Mock
    private TokenBlacklist tokenBlacklist;
    @Mock
    private JwtProperties jwtProperties;

    @InjectMocks
    private EmployeeServiceImpl employeeService;

    @BeforeEach
    void setUp() {
        BaseContext.setCurrentId(CURRENT_ID);
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    private Employee employee(Integer status) {
        Employee employee = new Employee();
        employee.setId(CURRENT_ID);
        employee.setUsername(USERNAME);
        employee.setName("管理员");
        employee.setPassword(PasswordHasher.hash("123456"));
        employee.setStatus(status);
        return employee;
    }

    private EmployeeLoginDTO loginDTO() {
        EmployeeLoginDTO dto = new EmployeeLoginDTO();
        dto.setUsername(USERNAME);
        dto.setPassword("123456");
        return dto;
    }

    @Test
    @DisplayName("不能禁用当前登录的账号")
    void cannotDisableSelf() {
        assertThatThrownBy(() -> employeeService.startOrStop(StatusConstant.DISABLE, CURRENT_ID))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不能禁用当前登录");

        verify(employeeMapper, never()).update(any());
    }

    @Test
    @DisplayName("禁用他人：落库后立刻作废其此前签发的令牌")
    void disableOtherEmployeeRevokesTokens() {
        when(jwtProperties.getAdminTtl()).thenReturn(7200000L);

        employeeService.startOrStop(StatusConstant.DISABLE, 2L);

        verify(employeeMapper).update(any(Employee.class));
        verify(tokenBlacklist).revokeEmployee(2L, 7200000L);
    }

    @Test
    @DisplayName("启用账号不需要作废令牌")
    void enableEmployeeDoesNotRevoke() {
        employeeService.startOrStop(StatusConstant.ENABLE, 2L);

        verify(employeeMapper).update(any(Employee.class));
        verify(tokenBlacklist, never()).revokeEmployee(anyLong(), anyLong());
    }

    @Test
    @DisplayName("非法状态值被拒绝，不会把任意数字写进 status")
    void rejectsIllegalStatusValue() {
        assertThatThrownBy(() -> employeeService.startOrStop(5, 2L))
                .isInstanceOf(BaseException.class);

        verify(employeeMapper, never()).update(any());
    }

    @Test
    @DisplayName("账号已被临时锁定时先拦下，不再比对密码")
    void loginRejectsWhenLocked() {
        when(loginAttemptGuard.isLocked(USERNAME)).thenReturn(true);

        assertThatThrownBy(() -> employeeService.login(loginDTO()))
                .isInstanceOf(AccountLockedException.class);

        verify(employeeMapper, never()).getByUsername(anyString());
    }

    @Test
    @DisplayName("账号不存在时不计入失败次数：否则攻击者可以用错密码把真实账号锁死")
    void missingAccountDoesNotCountFailure() {
        when(employeeMapper.getByUsername(USERNAME)).thenReturn(null);

        assertThatThrownBy(() -> employeeService.login(loginDTO()))
                .isInstanceOf(AccountNotFoundException.class);

        verify(loginAttemptGuard, never()).recordFailure(anyString());
    }

    @Test
    @DisplayName("密码错误计入失败次数，达到阈值时直接返回锁定")
    void wrongPasswordCountsFailure() {
        EmployeeLoginDTO dto = new EmployeeLoginDTO();
        dto.setUsername(USERNAME);
        dto.setPassword("wrong-password");
        when(employeeMapper.getByUsername(USERNAME)).thenReturn(employee(StatusConstant.ENABLE));
        when(loginAttemptGuard.recordFailure(USERNAME)).thenReturn(true);

        assertThatThrownBy(() -> employeeService.login(dto)).isInstanceOf(AccountLockedException.class);
    }

    @Test
    @DisplayName("密码错误但未达阈值时仍返回密码错误，不泄露锁定状态")
    void wrongPasswordBelowThreshold() {
        EmployeeLoginDTO dto = new EmployeeLoginDTO();
        dto.setUsername(USERNAME);
        dto.setPassword("wrong-password");
        when(employeeMapper.getByUsername(USERNAME)).thenReturn(employee(StatusConstant.ENABLE));
        when(loginAttemptGuard.recordFailure(USERNAME)).thenReturn(false);

        assertThatThrownBy(() -> employeeService.login(dto)).isInstanceOf(PasswordErrorException.class);
    }

    @Test
    @DisplayName("登录成功清零失败计数")
    void successfulLoginClearsFailureCount() {
        when(employeeMapper.getByUsername(USERNAME)).thenReturn(employee(StatusConstant.ENABLE));

        Employee result = employeeService.login(loginDTO());

        assertThat(result.getUsername()).isEqualTo(USERNAME);
        verify(loginAttemptGuard).clear(eq(USERNAME));
    }

    @Test
    @DisplayName("被禁用的账号即使密码正确也不能登录")
    void disabledAccountCannotLogin() {
        when(employeeMapper.getByUsername(USERNAME)).thenReturn(employee(StatusConstant.DISABLE));

        assertThatThrownBy(() -> employeeService.login(loginDTO()))
                .isInstanceOf(AccountLockedException.class);

        verify(loginAttemptGuard, never()).clear(anyString());
    }
}
