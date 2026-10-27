package com.sky.service;

import com.sky.context.BaseContext;
import com.sky.entity.AddressBook;
import com.sky.exception.AddressBookBusinessException;
import com.sky.mapper.AddressBookMapper;
import com.sky.service.impl.AddressBookServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 地址簿归属校验：里面是姓名、手机号、住址，越权读取的影响比订单更直接。
 */
@ExtendWith(MockitoExtension.class)
class AddressBookServiceImplTest {

    private static final Long USER_ID = 100L;

    @Mock
    private AddressBookMapper addressBookMapper;

    @InjectMocks
    private AddressBookServiceImpl addressBookService;

    @BeforeEach
    void setUp() {
        BaseContext.setCurrentId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    @Test
    @DisplayName("读取别人的地址 id 与不存在的 id 返回同一个错误，不泄露差异")
    void getByIdRejectsForeignId() {
        when(addressBookMapper.getByIdAndUserId(7L, USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> addressBookService.getById(7L))
                .isInstanceOf(AddressBookBusinessException.class);
    }

    @Test
    @DisplayName("修改前先校验归属，并且更新语句带 user_id 条件")
    void updateRequiresOwnershipAndScopesWrite() {
        AddressBook mine = new AddressBook();
        mine.setId(7L);
        mine.setConsignee("张三");
        mine.setPhone("13800000000");
        mine.setDetail("中关村大街1号");
        when(addressBookMapper.getByIdAndUserId(7L, USER_ID)).thenReturn(mine);

        addressBookService.update(mine);

        verify(addressBookMapper).updateByIdAndUserId(mine);
        verify(addressBookMapper, never()).update(any(AddressBook.class));
    }

    @Test
    @DisplayName("id 为空时不允许直接落到 update 语句上")
    void updateRejectsNullId() {
        AddressBook noId = new AddressBook();
        noId.setConsignee("张三");

        assertThatThrownBy(() -> addressBookService.update(noId))
                .isInstanceOf(AddressBookBusinessException.class);
        verify(addressBookMapper, never()).updateByIdAndUserId(any());
    }

    @Test
    @DisplayName("删除按 id + userId 双条件")
    void deleteIsUserScoped() {
        addressBookService.deleteById(9L);

        verify(addressBookMapper).deleteByIdAndUserId(9L, USER_ID);
        verify(addressBookMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("列表查询强制锁定到当前用户，不接受请求里带来的 user_id")
    void listForcesCurrentUser() {
        AddressBook probe = new AddressBook();
        probe.setUserId(999L); // 试图读别人的地址簿

        addressBookService.list(probe);

        verify(addressBookMapper).list(probe);
        org.assertj.core.api.Assertions.assertThat(probe.getUserId()).isEqualTo(USER_ID);
    }
}
