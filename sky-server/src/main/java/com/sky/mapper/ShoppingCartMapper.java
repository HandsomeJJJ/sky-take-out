package com.sky.mapper;

import com.sky.entity.ShoppingCart;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface ShoppingCartMapper {

    /**
     * 动态条件查询
     * @param shoppingCart
     * @return
     */
    List<ShoppingCart> list(ShoppingCart shoppingCart);

    /**
     * 根据id修改商品数量
     * @param shoppingCart
     */
    @Update("update shopping_cart set number = #{number} where id = #{id}")
    void updateNumberById(ShoppingCart shoppingCart);

    /**
     * 数据库内自增，避免"先查出来 +1 再覆盖写回"在并发下丢失更新。
     * 返回 0 表示已达单行数量上限。
     */
    @Update("update shopping_cart set number = number + 1 where id = #{id} and number < #{maxNumber}")
    int increaseNumberById(@Param("id") Long id, @Param("maxNumber") int maxNumber);

    /**
     * 一次加若干份（"再来一单"复用历史数量）。
     * 数量累加与上限判断都在 SQL 里完成，返回 0 表示加不完，由调用方按上限封顶。
     */
    @Update("update shopping_cart set number = number + #{delta} where id = #{id} and number + #{delta} <= #{maxNumber}")
    int increaseNumberByDelta(@Param("id") Long id, @Param("delta") int delta, @Param("maxNumber") int maxNumber);

    /** 把某一行的数量顶到上限，用于"再来一单"时数量超出的部分 */
    @Update("update shopping_cart set number = #{maxNumber} where id = #{id} and number < #{maxNumber}")
    int capNumberById(@Param("id") Long id, @Param("maxNumber") int maxNumber);

    /**
     * 数据库内自减，仅当数量仍大于 1 时生效；返回 0 表示应该删除这一行
     */
    @Update("update shopping_cart set number = number - 1 where id = #{id} and number > 1")
    int decreaseNumberById(@Param("id") Long id);

    /**
     * 插入购物车数据
     * @param shoppingCart
     */
    @Insert("insert into shopping_cart (name, user_id, dish_id, setmeal_id, dish_flavor, number, amount, image, create_time) " +
            " values (#{name},#{userId},#{dishId},#{setmealId},#{dishFlavor},#{number},#{amount},#{image},#{createTime})")
    void insert(ShoppingCart shoppingCart);

    /**
     * 根据用户id删除购物车数据
     * @param userId
     */
    @Delete("delete from shopping_cart where user_id = #{userId}")
    void deleteByUserId(Long userId);

    /**
     * 根据id删除购物车数据
     * @param id
     */
    @Delete("delete from shopping_cart where id = #{id}")
    void deleteById(Long id);

    /**
     * 删除自己的购物车行：id 是客户端传入的，必须带上 user_id 才不能删别人的记录
     */
    @Delete("delete from shopping_cart where id = #{id} and user_id = #{userId}")
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 菜品被删除时，把所有用户购物车里指向它的行一起清掉。
     * dish 表删了之后购物车行是悬空引用，下单复核会整单被拒，用户看不到原因也删不掉。
     */
    @Delete("<script>delete from shopping_cart where dish_id in " +
            "<foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach>" +
            "</script>")
    int deleteByDishIds(@Param("ids") List<Long> ids);

    /** 套餐被删除时同理 */
    @Delete("<script>delete from shopping_cart where setmeal_id in " +
            "<foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach>" +
            "</script>")
    int deleteBySetmealIds(@Param("ids") List<Long> ids);

    /** 还有多少购物车行在用这张图 */
    @Select("select count(1) from shopping_cart where image = #{image}")
    int countByImage(@Param("image") String image);

    /**
     * 批量插入购物车数据
     *
     * @param shoppingCartList
     */
    void insertBatch(List<ShoppingCart> shoppingCartList);
}
