package com.sky.service;

import com.sky.entity.Dish;
import com.sky.entity.DishFlavor;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.mapper.DishFlavorMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.service.impl.DishServiceImpl;
import com.sky.vo.DishVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 菜品查询与删除的两条口径：
 * 1. C 端菜单的"菜品+口味"是一次批量查口味，不是每个菜单查一次（N+1）；
 * 2. 删菜品只把图片交给回收流程，绝不在这里直接删 OSS 对象。
 */
@ExtendWith(MockitoExtension.class)
class DishServiceImplTest {

    @Mock
    private DishMapper dishMapper;
    @Mock
    private DishFlavorMapper dishFlavorMapper;
    @Mock
    private SetmealDishMapper setmealDishMapper;
    @Mock
    private SetmealMapper setmealMapper;
    @Mock
    private ShoppingCartMapper shoppingCartMapper;
    @Mock
    private MediaService mediaService;

    @InjectMocks
    private DishServiceImpl dishService;

    @Test
    @DisplayName("列表带口味：N 个菜品只查一次口味表，并按 dishId 归位")
    void batchesFlavorQuery() {
        Dish query = Dish.builder().categoryId(1L).build();
        when(dishMapper.list(query)).thenReturn(List.of(
                dish(1L, "img-1.png"), dish(2L, "img-2.png"), dish(3L, null)));
        when(dishFlavorMapper.getByDishIds(List.of(1L, 2L, 3L))).thenReturn(List.of(
                flavor(1L, "辣度", "微辣"),
                flavor(3L, "甜度", "无糖")));

        List<DishVO> result = dishService.listWithFlavor(query);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getFlavors()).extracting(DishFlavor::getValue).containsExactly("微辣");
        assertThat(result.get(1).getFlavors()).isEmpty();
        assertThat(result.get(2).getFlavors()).extracting(DishFlavor::getValue).containsExactly("无糖");

        verify(dishFlavorMapper, times(1)).getByDishIds(anyList());
        verify(dishFlavorMapper, never()).getByDishId(eq(1L));
    }

    @Test
    @DisplayName("没有菜品时不再发起口味查询，也不返回 null")
    void shortCircuitsWhenNoDish() {
        Dish query = Dish.builder().categoryId(99L).build();
        when(dishMapper.list(query)).thenReturn(List.of());

        assertThat(dishService.listWithFlavor(query)).isEmpty();
        verify(dishFlavorMapper, never()).getByDishIds(anyList());
    }

    @Test
    @DisplayName("停售菜品删除后，图片进入回收流程；商品还在引用时由回收流程判断")
    void handsImagesToRetentionOnDelete() {
        when(dishMapper.getById(1L)).thenReturn(dish(1L, "https://bucket.host/img-1.png"));
        when(dishMapper.getById(2L)).thenReturn(dish(2L, "https://bucket.host/img-2.png"));
        when(setmealDishMapper.getSetmealIdsByDishIds(List.of(1L, 2L))).thenReturn(List.of());
        when(shoppingCartMapper.deleteByDishIds(List.of(1L, 2L))).thenReturn(1);

        dishService.deleteBatch(List.of(1L, 2L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> images = ArgumentCaptor.forClass(List.class);
        verify(mediaService).retireIfUnreferenced(images.capture());
        assertThat(images.getValue()).containsExactly(
                "https://bucket.host/img-1.png", "https://bucket.host/img-2.png");
    }

    @Test
    @DisplayName("起售中的菜品不允许删除，图片也就不会被登记回收")
    void rejectsDeletingEnabledDish() {
        Dish onSale = dish(1L, "https://bucket.host/img-1.png");
        onSale.setStatus(1);
        when(dishMapper.getById(1L)).thenReturn(onSale);

        assertThatThrownBy(() -> dishService.deleteBatch(List.of(1L)))
                .isInstanceOf(DeletionNotAllowedException.class);

        verify(mediaService, never()).retireIfUnreferenced(anyList());
    }

    private static Dish dish(Long id, String image) {
        return Dish.builder()
                .id(id)
                .name("菜品" + id)
                .categoryId(1L)
                .price(new BigDecimal("18.00"))
                .image(image)
                .status(0)
                .build();
    }

    private static DishFlavor flavor(Long dishId, String name, String value) {
        return DishFlavor.builder().dishId(dishId).name(name).value(value).build();
    }
}
