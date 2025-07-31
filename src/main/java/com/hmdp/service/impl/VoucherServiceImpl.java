package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private Cache cache;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券，之后要做秒刷券详情的预热
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);

        //保存秒杀库存到redis中
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
    }

    @Override
    public Result queryVoucherDetail(Long voucherId) {// 这里理论上要将秒杀热点券和普通券分表，才更合理
        // 另外，由于认为秒杀券详情信息很少更改，未实现优惠券修改的一致性逻辑和删除的逻辑

        String key = RedisConstants.CACHE_VOUCHER_DETAIL + voucherId;

        Voucher v = (Voucher) cache.get(key,
                k -> {
                    String json = stringRedisTemplate.opsForValue().get(key);
                    if(StrUtil.isNotBlank(json)) {
                        log.debug("get data from redis");
                        return JSONUtil.toBean(json, Voucher.class);
                    }
                    Voucher voucher = getById(voucherId);
                    if (voucher == null || voucher.getType()==1) {
                        return null;
                    }
                    log.debug("get data from database");
                    stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(voucher), 120, TimeUnit.SECONDS);
                    return voucher;
                });
        if (v == null) {
            return Result.fail("没有找到对应的优惠券");
        }
        return Result.ok(v);
    }


}
