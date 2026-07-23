/*
 * Copyright 2026 Franz Schöning
 * Project: https://www.zeroz4j.com
 * Author: Franz Schöning - Principal Enterprise Architect (https://www.franzschoning.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zeroz4j.example.client;

import com.zeroz4j.api.RmiSecurityContext;
import com.zeroz4j.client.ServerEvents;
import com.zeroz4j.example.api.ChatService;
import com.zeroz4j.example.api.ChatService_Stub;
import com.zeroz4j.example.api.ChatEvents;
import com.zeroz4j.example.model.ChatMessage;
import com.zeroz4j.ui.component.*;
import com.zeroz4j.ui.layout.*;
import com.zeroz4j.ui.signals.Computed;
import com.zeroz4j.ui.signals.Effect;
import com.zeroz4j.ui.signals.ValueSignal;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat view built on zeroz4j Signals end-to-end:
 * server events arrive on typed {@link ChatEvents} topics, a reducer folds them into the
 * {@code messages} state signal, and {@code Effect}/{@code Computed} derive the UI from it.
 */
public class ChatView extends Card {

    private final ChatService chatService = new ChatService_Stub();

    // State: the message list. Updated only with immutable copies so equality-based
    // change detection in ValueSignal.set() sees every change.
    private final ValueSignal<List<ChatMessage>> messages = new ValueSignal<>(new ArrayList<>());
    private final ValueSignal<String> errorMessage = new ValueSignal<>("");

    // Derived state: recomputes lazily when messages change.
    private final Computed<Integer> messageCount = new Computed<>(() -> messages.get().size());

    private final List<Effect.Disposable> subscriptions = new ArrayList<>();

    public ChatView() {
        super();

        addClassName("h-[600px]");
        addClassName("flex");
        addClassName("flex-col");

        HorizontalLayout titleRow = new HorizontalLayout();
        titleRow.addClassName("items-center");
        titleRow.addClassName("gap-2");
        titleRow.add(new CardTitle("Chat"));

        Badge countBadge = new Badge();
        countBadge.addClassName("badge-primary");
        titleRow.add(countBadge);
        add(titleRow);

        Div messageListContainer = new Div();
        messageListContainer.addClassName("flex-1");
        messageListContainer.addClassName("bg-base-200");
        messageListContainer.addClassName("rounded-box");
        messageListContainer.addClassName("p-4");
        messageListContainer.addClassName("mb-4");
        messageListContainer.addClassName("overflow-y-auto");
        add(messageListContainer);

        HorizontalLayout inputLayout = new HorizontalLayout();
        inputLayout.addClassName("gap-2");
        inputLayout.addClassName("w-full");

        TextField inputField = new TextField("Type a message...");
        inputField.addClassName("flex-1");

        Button sendButton = new Button("Send");
        sendButton.addClassName("btn-primary");

        inputLayout.add(inputField, sendButton);
        add(inputLayout);

        Div errorDiv = new Div();
        errorDiv.addClassName("text-error");
        errorDiv.addClassName("mt-2");
        add(errorDiv);

        sendButton.addClickListener(e -> {
            String text = inputField.getValue();
            if (text != null && !text.trim().isEmpty()) {
                inputField.setValue("");
                try {
                    chatService.sendMessage(text);
                    errorMessage.set("");
                    // No manual refresh: our own MESSAGE_POSTED push updates the list.
                } catch (Exception ex) {
                    errorMessage.set("Failed to send message: " + ex.getMessage());
                }
            }
        });

        subscriptions.add(Effect.create(() -> errorDiv.setText(errorMessage.get())));

        subscriptions.add(Effect.create(() ->
                countBadge.setText(messageCount.get() + " messages")));

        subscriptions.add(Effect.create(() -> {
            messageListContainer.getElement().setInnerHTML(""); // Clear
            String currentUser = RmiSecurityContext.getUsername();
            for (ChatMessage msg : messages.get()) {
                Div msgDiv = new Div();
                msgDiv.addClassName("chat");

                boolean isMe = currentUser != null && currentUser.equals(msg.getSender());
                if (isMe) {
                    msgDiv.addClassName("chat-end");
                } else {
                    msgDiv.addClassName("chat-start");
                }

                Div header = new Div();
                header.addClassName("chat-header");
                header.setText(msg.getSender());

                ChatBubble bubble = new ChatBubble(msg.getText());
                if (isMe) {
                    bubble.addClassName("chat-bubble-primary");
                } else {
                    bubble.addClassName("chat-bubble-secondary");
                }

                msgDiv.add(header, bubble);
                messageListContainer.add(msgDiv);
            }
        }));

        // Subscribe BEFORE fetching history so no message can fall into the gap between
        // the server building the snapshot and this client starting to listen.
        subscriptions.add(ServerEvents.on(ChatEvents.MESSAGE_POSTED, msg ->
                messages.update(current -> appendIfAbsent(current, msg))));
        subscriptions.add(ServerEvents.on(ChatEvents.HISTORY_CLEARED, ignored ->
                messages.set(new ArrayList<>())));

        loadHistory();
    }

    private void loadHistory() {
        new Thread(() -> {
            try {
                List<ChatMessage> history = chatService.getHistory();
                // Merge instead of replace: keep messages that were pushed while the
                // snapshot was in flight (deduped via ChatMessage value equality).
                messages.update(current -> {
                    List<ChatMessage> merged = new ArrayList<>(history);
                    for (ChatMessage pushed : current) {
                        if (!merged.contains(pushed)) {
                            merged.add(pushed);
                        }
                    }
                    return merged;
                });
                errorMessage.set("");
            } catch (Exception ex) {
                errorMessage.set("Failed to load chat history: " + ex.getMessage());
            }
        }).start();
    }

    private static List<ChatMessage> appendIfAbsent(List<ChatMessage> current, ChatMessage msg) {
        if (current.contains(msg)) {
            return current;
        }
        List<ChatMessage> next = new ArrayList<>(current);
        next.add(msg);
        return next;
    }

    /**
     * Releases all push subscriptions and effects. Call when the view is permanently removed.
     */
    public void dispose() {
        for (Effect.Disposable subscription : subscriptions) {
            subscription.dispose();
        }
        subscriptions.clear();
    }
}
