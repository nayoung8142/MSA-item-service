package com.nayoung.itemservice.domain.item;

import com.nayoung.itemservice.domain.item.log.ItemUpdateLog;
import com.nayoung.itemservice.domain.item.log.ItemUpdateLogRepository;
import com.nayoung.itemservice.domain.item.log.OrderStatus;
import com.nayoung.itemservice.domain.shop.Shop;
import com.nayoung.itemservice.domain.shop.ShopRepository;
import com.nayoung.itemservice.domain.shop.ShopService;
import com.nayoung.itemservice.exception.ExceptionCode;
import com.nayoung.itemservice.exception.ItemException;
import com.nayoung.itemservice.web.dto.ItemDto;
import com.nayoung.itemservice.web.dto.ItemStockToUpdateDto;
import com.nayoung.itemservice.web.dto.ShopDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RedissonItemServiceTest {

    @Autowired private RedissonItemService redissonItemService;
    @Autowired private ItemService itemService;
    @Autowired private ItemRepository itemRepository;
    @Autowired private ItemUpdateLogRepository itemUpdateLogRepository;
    @Autowired private ShopService shopService;
    @Autowired private ShopRepository shopRepository;

    private final Long ORDER_ID = 3L;
    private final Long CUSTOMER_ACCOUNT_ID = 2L;
    private final String SUWON = "suwon";

    String[] itemName = {"apple", "kiwi"};

    @BeforeEach
    public void beforeEach() {

        itemRepository.deleteAll();
        itemUpdateLogRepository.deleteAll();
        shopRepository.deleteAll();

        ShopDto request = ShopDto.builder()
                .location(SUWON).name(SUWON + 1).build();
        ShopDto shopDto = shopService.create(request);

        for (String s : itemName) {
            ItemDto itemDto = ItemDto.builder()
                    .name(s).price(1200L)
                    .stock(20L)
                    .shopId(shopDto.getId()).build();
            itemService.create(itemDto);
        }
    }

    @AfterEach
    public void afterEach() {
//        itemRepository.deleteAll();
//        itemUpdateLogRepository.deleteAll();
//        shopRepository.deleteAll();
    }

    @Test
    void 모든_상품_재고_충분 () {
        List<ItemStockToUpdateDto> itemStockToUpdateDtos = getItemStockToUpdateDtos();
        Map<Long, Long> previousStockMap = getPreviousStock(itemStockToUpdateDtos);

        List<ItemStockToUpdateDto> response = itemStockToUpdateDtos.parallelStream()
                        .map(i -> redissonItemService.decreaseItemStock(i))
                                .collect(Collectors.toList());

        Assertions.assertTrue(response.stream()
                .allMatch(i -> Objects.equals(OrderStatus.SUCCEED, i.getOrderStatus())));

        List<ItemUpdateLog> itemUpdateLogs = itemUpdateLogRepository.findAllByOrderId(response.get(0).getOrderId());
        Assertions.assertTrue(itemUpdateLogs.stream()
                .allMatch(itemUpdateLog -> itemUpdateLog.getOrderStatus() == OrderStatus.SUCCEED));

        for(ItemUpdateLog itemUpdateLog : itemUpdateLogs) {
            Long previousStock = previousStockMap.get(itemUpdateLog.getItemId());
            Item item = itemRepository.findById(itemUpdateLog.getItemId()).orElseThrow();
            Assertions.assertEquals(previousStock, item.getStock() + itemUpdateLog.getQuantity());
        }
    }

    @Test
    void 일부_상품_재고_부족 () {
        List<ItemStockToUpdateDto> itemStockToUpdateDtos = getItemStockToUpdateDtosByExcessQuantity();
        Map<Long, Long> previousStockMap = getPreviousStock(itemStockToUpdateDtos);

        List<ItemStockToUpdateDto> response = itemStockToUpdateDtos.stream()
                .map(i -> redissonItemService.decreaseItemStock(i))
                .collect(Collectors.toList());

        Assertions.assertTrue(response.stream()
                .noneMatch(r -> Objects.equals(OrderStatus.SUCCEED, r.getOrderStatus())));

        // Log 확인
        List<ItemUpdateLog> itemUpdateLogs = itemUpdateLogRepository.findAllByOrderId(response.get(0).getOrderId());
        Assertions.assertTrue(itemUpdateLogs.stream()
                .noneMatch(i -> Objects.equals(OrderStatus.SUCCEED, i.getOrderStatus())));
        Assertions.assertTrue(itemUpdateLogs.stream().allMatch(i -> i.getQuantity() == 0L));

        for(ItemStockToUpdateDto itemStockToUpdateDto : itemStockToUpdateDtos) {
            Item item = itemRepository.findById(itemStockToUpdateDto.getItemId()).orElseThrow();
            Assertions.assertEquals(previousStockMap.get(item.getId()), item.getStock());
        }
    }

    private List<ItemStockToUpdateDto> getItemStockToUpdateDtos() {
        List<Item> items = itemRepository.findAll();
        assert(items.size() > 0);

        return items.stream()
                .map(i -> ItemStockToUpdateDto.builder()
                        .shopId(i.getShop().getId()).orderId(1L)
                        .itemId(i.getId())
                        .quantity(i.getStock() / 2).build())
                .collect(Collectors.toList());
    }

    private List<ItemStockToUpdateDto> getItemStockToUpdateDtosByExcessQuantity() {
        List<Item> items = itemRepository.findAll();
        assert(items.size() == 2);

        List<ItemStockToUpdateDto> orderItemRequests = new ArrayList<>();
        orderItemRequests.add(ItemStockToUpdateDto.builder()
                .shopId(items.get(0).getShop().getId())
                .itemId(items.get(0).getId())
                .quantity(items.get(0).getStock() / 2).build());

        orderItemRequests.add(ItemStockToUpdateDto.builder()
                .shopId(items.get(1).getShop().getId())
                .itemId(items.get(1).getId())
                .quantity(items.get(1).getStock() + 1).build()); // 재고보다 많은 주문

        return orderItemRequests;
    }

    private Map<Long, Long> getPreviousStock(List<ItemStockToUpdateDto> requests) {
        Map<Long, Long> previousStock = new HashMap<>();
        for(ItemStockToUpdateDto request : requests) {
            Item item = itemRepository.findById(request.getItemId())
                    .orElseThrow(() -> new ItemException(ExceptionCode.NOT_FOUND_ITEM));
            previousStock.put(item.getId(), item.getStock());
        }
        return previousStock;
    }
}