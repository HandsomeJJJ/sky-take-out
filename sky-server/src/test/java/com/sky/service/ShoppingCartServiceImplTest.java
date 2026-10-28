package com.sky.service;

import com.sky.context.BaseContext;
import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.Dish;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.service.impl.ShoppingCartServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 加购的两道闸：门店必须在营业，商品必须在售。
 */
@ExtendWith(MockitoExtension.class)
class ShoppingCartServiceImplTest {

    private static final Long USER_ID = 100L;
    private static final Long DISH_ID = 11L;

    @Mock
    private ShoppingCartMapper shoppingCartMapper;
    @Mock
    private DishMapper dishMapper;
    @Mock
    private SetmealMapper setmealMapper;
    @Mock
    private ShopStatusService shopStatusService;

    @InjectMocks
    private ShoppingCartServiceImpl shoppingCartService;

    @BeforeEach
    void setUp() {
        BaseContext.setCurrentId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    private ShoppingCartDTO dishDTO() {
        ShoppingCartDTO dto = new ShoppingCartDTO();
        dto.setDishId(DISH_ID);
        return dto;
    }

    private Dish dish(Integer status) {
        Dish dish = new Dish();
        dish.setId(DISH_ID);
        dish.setName("宫保鸡丁");
        dish.setPrice(new BigDecimal("12.00"));
        dish.setStatus(status);
        return dish;
    }

    @Test
    @DisplayName("打烊时不允许加购")
    void rejectsAddWhenShopClosed() {
        when(shopStatusService.isOpen()).thenReturn(false);

        assertThatThrownBy(() -> shoppingCartService.addShoppingCart(dishDTO()))
                .isInstanceOf(ShoppingCartBusinessException.class)
                .hasMessageContaining("打烊");

        verify(shoppingCartMapper, never()).insert(any());
    }

    @Test
    @DisplayName("停售菜品不允许加购")
    void rejectsAddWhenDishOffSale() {
        when(shopStatusService.isOpen()).thenReturn(true);
        when(shoppingCartMapper.list(any())).thenReturn(List.of());
        when(dishMapper.getById(DISH_ID)).thenReturn(dish(0));

        assertThatThrownBy(() -> shoppingCartService.addShoppingCart(dishDTO()))
                .isInstanceOf(ShoppingCartBusinessException.class);

        verify(shoppingCartMapper, never()).insert(any());
    }

    @Test
    @DisplayName("已删除菜品（查不到）不允许加购")
    void rejectsAddWhenDishMissing() {
        when(shopStatusService.isOpen()).thenReturn(true);
        when(shoppingCartMapper.list(any())).thenReturn(List.of());
        when(dishMapper.getById(DISH_ID)).thenReturn(null);

        assertThatThrownBy(() -> shoppingCartService.addShoppingCart(dishDTO()))
                .isInstanceOf(ShoppingCartBusinessException.class);

        verify(shoppingCartMapper, never()).insert(any());
    }

    @Test
    @DisplayName("在售商品加购时价格取当前菜品价，不取客户端值")
    void addUsesCurrentDishPrice() {
        when(shopStatusService.isOpen()).thenReturn(true);
        when(shoppingCartMapper.list(any())).thenReturn(List.of());
        when(dishMapper.getById(DISH_ID)).thenReturn(dish(1));

        shoppingCartService.addShoppingCart(dishDTO());

        verify(shoppingCartMapper).insert(any());
        verify(shoppingCartMapper, never()).increaseNumberById(anyLong(), anyInt());
    }

    @Test
    @DisplayName("同一商品已在购物车时只做数据库内自增，不重复插入")
    void addIncreasesExistingRow() {
        when(shopStatusService.isOpen()).thenReturn(true);
        when(shoppingCartMapper.list(any())).thenReturn(List.of(
                com.sky.entity.ShoppingCart.builder().id(7L).number(1).build()));
        when(shoppingCartMapper.increaseNumberById(7L, 99)).thenReturn(1);

        shoppingCartService.addShoppingCart(dishDTO());

        verify(shoppingCartMapper).increaseNumberById(7L, 99);
        verify(shoppingCartMapper, never()).insert(any());
        verify(dishMapper, never()).getById(anyLong());
    }

    @Test
    @DisplayName("超出单行上限时报错，而不是静默截断")
    void addRejectsWhenExceedingMaxNumber() {
        when(shopStatusService.isOpen()).thenReturn(true);
        when(shoppingCartMapper.list(any())).thenReturn(List.of(
                com.sky.entity.ShoppingCart.builder().id(7L).number(99).build()));
        when(shoppingCartMapper.increaseNumberById(7L, 99)).thenReturn(0);

        assertThatThrownBy(() -> shoppingCartService.addShoppingCart(dishDTO()))
                .isInstanceOf(ShoppingCartBusinessException.class)
                .hasMessageContaining("99");
    }
}
