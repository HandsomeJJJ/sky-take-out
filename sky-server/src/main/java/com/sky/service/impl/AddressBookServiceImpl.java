package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.entity.AddressBook;
import com.sky.exception.AddressBookBusinessException;
import com.sky.mapper.AddressBookMapper;
import com.sky.service.AddressBookService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
public class AddressBookServiceImpl implements AddressBookService {
    @Autowired
    private AddressBookMapper addressBookMapper;

    /**
     * 条件查询
     *
     * @param addressBook
     * @return
     */
    public List<AddressBook> list(AddressBook addressBook) {
        // 地址簿是C端数据，列表查询必须锁定在当前登录用户上
        addressBook.setUserId(BaseContext.getCurrentId());
        return addressBookMapper.list(addressBook);
    }

    /**
     * 新增地址
     *
     * @param addressBook
     */
    public void save(AddressBook addressBook) {
        addressBook.setId(null);
        addressBook.setUserId(BaseContext.getCurrentId());
        addressBook.setIsDefault(0);
        addressBookMapper.insert(addressBook);
    }

    /**
     * 根据id查询
     *
     * @param id
     * @return
     */
    public AddressBook getById(Long id) {
        // 地址含手机号与住址，按 id 直查等于任何人可遍历读取
        AddressBook addressBook = addressBookMapper.getByIdAndUserId(id, BaseContext.getCurrentId());
        if (addressBook == null) {
            throw new AddressBookBusinessException("地址不存在");
        }
        return addressBook;
    }

    /**
     * 根据id修改地址
     *
     * @param addressBook
     */
    public void update(AddressBook addressBook) {
        requireOwned(addressBook.getId());
        addressBook.setUserId(BaseContext.getCurrentId());
        // 归属字段不允许被请求改写
        addressBookMapper.updateByIdAndUserId(addressBook);
    }

    /**
     * 设置默认地址
     *
     * @param addressBook
     */
    @Transactional(rollbackFor = Exception.class)
    public void setDefault(AddressBook addressBook) {
        requireOwned(addressBook.getId());

        //1、将当前用户的所有地址修改为非默认地址 update address_book set is_default = ? where user_id = ?
        addressBook.setIsDefault(0);
        addressBook.setUserId(BaseContext.getCurrentId());
        addressBookMapper.updateIsDefaultByUserId(addressBook);

        //2、将当前地址改为默认地址 update address_book set is_default = ? where id = ? and user_id = ?
        addressBook.setIsDefault(1);
        addressBookMapper.updateByIdAndUserId(addressBook);
    }

    /**
     * 根据id删除地址
     *
     * @param id
     */
    public void deleteById(Long id) {
        addressBookMapper.deleteByIdAndUserId(id, BaseContext.getCurrentId());
    }

    /**
     * 校验地址属于当前登录用户。不存在与不属于同一人一律按"不存在"响应，避免用错误信息探测他人数据。
     */
    private void requireOwned(Long id) {
        if (id == null || addressBookMapper.getByIdAndUserId(id, BaseContext.getCurrentId()) == null) {
            throw new AddressBookBusinessException("地址不存在");
        }
    }

}
