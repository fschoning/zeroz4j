package com.zeroz4j.example.model;

import com.zeroz4j.api.Portable;
import com.zeroz4j.api.LiveSync;
import java.util.ArrayList;
import java.util.List;

@Portable
@LiveSync
public class LiveChatState {
    private List<ChatMessage> messages = new ArrayList<>();

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessage> messages) {
        this.messages = messages;
    }
}
