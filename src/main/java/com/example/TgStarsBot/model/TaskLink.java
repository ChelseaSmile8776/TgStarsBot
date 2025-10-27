package com.example.TgStarsBot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity(name = "taskLinkTable")
public class TaskLink {

    @Id
    private Long chatId;

    private String userName;

    private String link;

    private Long targetSubs;

    @jakarta.persistence.Column(name = "current_subs")
    private Long currentSubs = 0L;

    public Long getCurrentSubs() {
        return currentSubs;
    }

    public void setCurrentSubs(Long currentSubs) {
        this.currentSubs = currentSubs;
    }

    public Long getTargetSubs() {
        return targetSubs;
    }

    public void setTargetSubs(Long targetSubs) {
        this.targetSubs = targetSubs;
    }

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public Long getChatId() {
        return chatId;
    }

    public void setChatId(Long chatId) {
        this.chatId = chatId;
    }

    @Override
    public String toString() {
        return "TaskLink{" +
                "chatId=" + chatId +
                ", userName='" + userName + '\'' +
                ", link='" + link + '\'' +
                ", targetSubs=" + targetSubs +
                '}';
    }
}
