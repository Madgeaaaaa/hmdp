package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.limiter.annotation.RateLimiter;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;


@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    @RateLimiter(
            key = "limit/seckill",
            window = 100,
            limit = 5,
            message = "秒杀活动太火爆，请稍后再试",
            type = RateLimiter.LimitType.METHOD
    )
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {

        return voucherOrderService.seckillVoucher(voucherId);
    }
}
