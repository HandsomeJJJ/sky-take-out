package com.sky.service.impl;

import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.context.BaseContext;
import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.ShoppingCart;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.service.ShoppingCartService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class ShoppingCartServiceImpl implements ShoppingCartService {

    /** 同一个商品在一条购物车记录里的数量上限 */
    private static final int MAX_NUMBER_PER_ITEM = 99;

    private final ShoppingCartMapper shoppingCartMapper;
    private final DishMapper dishMapper;
    private final SetmealMapper setmealMapper;

    public ShoppingCartServiceImpl(ShoppingCartMapper shoppingCartMapper,
                                   DishMapper dishMapper,
                                   SetmealMapper setmealMapper) {
        this.shoppingCartMapper = shoppingCartMapper;
        this.dishMapper = dishMapper;
        this.setmealMapper = setmealMapper;
    }

    /**
     * 添加购物车
     * @param shoppingCartDTO
     */
    public void addShoppingCart(ShoppingCartDTO shoppingCartDTO) {
        if (shoppingCartDTO == null
                || (shoppingCartDTO.getDishId() == null && shoppingCartDTO.getSetmealId() == null)) {
            throw new ShoppingCartBusinessException(MessageConstant.PARAM_ERROR);
        }

        ShoppingCart query = new ShoppingCart();
        BeanUtils.copyProperties(shoppingCartDTO, query);
        Long userId = BaseContext.getCurrentId();
        query.setUserId(userId);

        List<ShoppingCart> list = shoppingCartMapper.list(query);

        //如果已经存在了，只需要将数量加一
        if (list != null && !list.isEmpty()) {
            ShoppingCart cart = list.get(0);
            // 由数据库做 number = number + 1，避免"读出来 +1 再整体覆盖"在并发点击时丢失更新
            int rows = shoppingCartMapper.increaseNumberById(cart.getId(), MAX_NUMBER_PER_ITEM);
            if (rows != 1) {
                throw new ShoppingCartBusinessException("单个商品最多添加 " + MAX_NUMBER_PER_ITEM + " 份");
            }
            return;
        }

        //如果不存在，需要插入一条购物车数据
        //判断本次添加到购物车的是菜品还是套餐
        Long dishId = shoppingCartDTO.getDishId();
        if (dishId != null) {
            //本次添加到购物车的是菜品
            Dish dish = dishMapper.getById(dishId);
            requireOnSale(dish == null ? null : dish.getStatus(),
                    dish == null ? null : dish.getName());
            query.setName(dish.getName());
            query.setImage(dish.getImage());
            query.setAmount(dish.getPrice());
            query.setSetmealId(null);
        } else {
            //本次添加到购物车的是套餐
            Setmeal setmeal = setmealMapper.getById(shoppingCartDTO.getSetmealId());
            requireOnSale(setmeal == null ? null : setmeal.getStatus(),
                    setmeal == null ? null : setmeal.getName());
            query.setName(setmeal.getName());
            query.setImage(setmeal.getImage());
            query.setAmount(setmeal.getPrice());
            query.setDishId(null);
        }
        query.setNumber(1);
        query.setId(null);
        query.setUserId(userId);
        query.setCreateTime(LocalDateTime.now());
        shoppingCartMapper.insert(query);
    }

    /**
     * 停售或被删除的商品不允许再进购物车，否则会在下单时才炸。
     */
    private void requireOnSale(Integer status, String name) {
        if (name == null) {
            throw new ShoppingCartBusinessException("商品不存在");
        }
        if (!StatusConstant.ENABLE.equals(status)) {
            throw new ShoppingCartBusinessException("商品已售罄：" + name);
        }
    }

    /**
     * 查看购物车
     * @return
     */
    public List<ShoppingCart> showShoppingCart() {
        //获取到当前微信用户的id
        Long userId = BaseContext.getCurrentId();
        ShoppingCart shoppingCart = ShoppingCart.builder()
                .userId(userId)
                .build();
        return shoppingCartMapper.list(shoppingCart);
    }

    /**
     * 清空购物车
     */
    public void cleanShoppingCart() {
        //获取到当前微信用户的id
        Long userId = BaseContext.getCurrentId();
        shoppingCartMapper.deleteByUserId(userId);
    }

    /**
     * 删除购物车中一个商品
     * @param shoppingCartDTO
     */
    public void subShoppingCart(ShoppingCartDTO shoppingCartDTO) {
        ShoppingCart shoppingCart = new ShoppingCart();
        BeanUtils.copyProperties(shoppingCartDTO, shoppingCart);
        //设置查询条件，查询当前登录用户的购物车数据
        Long userId = BaseContext.getCurrentId();
        shoppingCart.setUserId(userId);

        List<ShoppingCart> list = shoppingCartMapper.list(shoppingCart);

        if (list == null || list.isEmpty()) {
            return;
        }

        ShoppingCart cart = list.get(0);
        // 先尝试自减；减不动了（数量为 1 或已经被人并发减掉了）就删行
        int rows = shoppingCartMapper.decreaseNumberById(cart.getId());
        if (rows != 1) {
            shoppingCartMapper.deleteByIdAndUserId(cart.getId(), userId);
        }
    }
}
