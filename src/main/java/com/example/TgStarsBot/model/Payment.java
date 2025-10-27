package com.example.TgStarsBot.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.sql.Timestamp;

@Entity(name = "paymentsHistoryTable")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // кто платил
    private Long chatId;
    private String userName;

    // что оплатили
    private String target;      // "ad" (рекламный) или "regular" (обычный) — из payload
    private String method;      // "stars", "sbp", "crypto" и т.п.
    private String currency;    // "XTR" для Telegram Stars
    private Integer amountStars; // сумма в звёздах (не *100)

    // служебные идентификаторы из Telegram
    private String telegramPaymentChargeId;  // sp.getTelegramPaymentChargeId()
    private String providerPaymentChargeId;  // sp.getProviderPaymentChargeId()

    private String payload;    // invoice payload, на всякий случай
    private Timestamp createdAt;

    // getters/setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getChatId() { return chatId; }
    public void setChatId(Long chatId) { this.chatId = chatId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Integer getAmountStars() { return amountStars; }
    public void setAmountStars(Integer amountStars) { this.amountStars = amountStars; }

    public String getTelegramPaymentChargeId() { return telegramPaymentChargeId; }
    public void setTelegramPaymentChargeId(String telegramPaymentChargeId) { this.telegramPaymentChargeId = telegramPaymentChargeId; }

    public String getProviderPaymentChargeId() { return providerPaymentChargeId; }
    public void setProviderPaymentChargeId(String providerPaymentChargeId) { this.providerPaymentChargeId = providerPaymentChargeId; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
}
