# Chat gia đình — API contract

The Android app now includes a private family-room interface. These authenticated endpoints must be implemented on the API server before messages can synchronize across different phones. The current GitHub repository contains the Android client and build workflow, but not the deployed API server source.

All routes require the existing `Authorization: Bearer <token>` account token. The server must derive the sender from that token; clients must never choose their own sender identity.

## Rooms

- `GET /api/family/chats` → `{"chats":[{"id":"room-id","name":"Gia đình mình","member_count":4,"last_message":"Mọi người ăn cơm nhé","updated_at":1794300000,"unread_count":2}]}`
- `POST /api/family/chats`, body `{"name":"Gia đình mình"}` → create a private family room.
- `POST /api/family/chats/join`, body `{"invite_code":"AB12CD"}` → join a room after validating the invite code.

## Invites

- `POST /api/family/chats/{chat_id}/invite` → `{"invite_code":"AB12CD"}`

Invite codes should be random and expiring. Only current members may create invite codes.

## Messages

- `GET /api/family/chats/{chat_id}/messages?limit=50` → `{"messages":[{"id":"message-id","sender_name":"Mẹ","body":"Mọi người về nhà nhé","created_at":1794300000,"mine":false}]}`
- `POST /api/family/chats/{chat_id}/messages`, body `{"body":"Con về lúc 6 giờ"}` → save and return the new message.

The server must check room membership on every read/write, validate message length (1–2,000 characters), generate timestamps server-side, and prevent non-members from reading messages. The Android client refreshes an open room every six seconds; this is polling, not WebSocket realtime.
