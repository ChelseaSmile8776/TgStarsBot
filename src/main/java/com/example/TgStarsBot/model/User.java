package com.example.TgStarsBot.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.sql.Timestamp;

@Entity(name = "usersDataTable")
public class User {

    @Id
    private Long chatId;

    private String userName;

    private String refCode;

    private String referredBy;

    private Integer balance;

    private Integer adsBalance;

    private Integer withdrawnBalance;

    private Timestamp registeredAt;

    private String activeTask;

    public String getActiveTask() {
        return activeTask;
    }

    public void setActiveTask(String activeTask) {
        this.activeTask = activeTask;
    }

    public Long getChatId() {
        return chatId;
    }

    public void setChatId(Long chatId) {
        this.chatId = chatId;
    }

    public Timestamp getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(Timestamp registeredAt) {
        this.registeredAt = registeredAt;
    }

    public Integer getWithdrawnBalance() {
        return withdrawnBalance;
    }

    public void setWithdrawnBalance(Integer withdrawnBalance) {
        this.withdrawnBalance = withdrawnBalance;
    }

    public Integer getAdsBalance() {
        return adsBalance;
    }

    public void setAdsBalance(Integer adsBalance) {
        this.adsBalance = adsBalance;
    }

    public Integer getBalance() {
        return balance;
    }

    public void setBalance(Integer balance) {
        this.balance = balance;
    }

    public String getRefCode() {
        return refCode;
    }

    public void setRefCode(String refCode) {
        this.refCode = refCode;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getReferredBy() {
        return referredBy;
    }

    public void setReferredBy(String referredBy) {
        this.referredBy = referredBy;
    }

    @Override
    public String toString() {
        return "User{" +
                "chatId=" + chatId +
                ", userName='" + userName + '\'' +
                ", refCode='" + refCode + '\'' +
                ", balance=" + balance +
                ", adsBalance=" + adsBalance +
                ", withdrawnBalance=" + withdrawnBalance +
                ", registeredAt=" + registeredAt +
                '}';
    }
}
