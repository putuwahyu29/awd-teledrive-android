package com.awd.teledrive.data.repository

import com.awd.teledrive.data.remote.TelegramClient
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShareRepository @Inject constructor(
    private val telegramClient: TelegramClient
) {
    fun getFolderInviteLink(chatId: Long, callback: (String?) -> Unit) {
        telegramClient.send(TdApi.GetChat(chatId)) { result ->
            if (result is TdApi.Chat) {
                // In some versions, the link is in Chat.inviteLink, in others we might need to get full info
                // Let's try to get full info if it's a supergroup
                val type = result.type
                if (type is TdApi.ChatTypeSupergroup) {
                    telegramClient.send(TdApi.GetSupergroupFullInfo(type.supergroupId)) { fullInfoResult ->
                        if (fullInfoResult is TdApi.SupergroupFullInfo) {
                            val inviteLink = fullInfoResult.inviteLink
                            if (inviteLink != null) {
                                callback(inviteLink.inviteLink)
                            } else {
                                createInviteLink(chatId, callback)
                            }
                        } else {
                            createInviteLink(chatId, callback)
                        }
                    }
                } else {
                    createInviteLink(chatId, callback)
                }
            } else {
                callback(null)
            }
        }
    }

    private fun createInviteLink(chatId: Long, callback: (String?) -> Unit) {
        telegramClient.send(TdApi.CreateChatInviteLink(chatId, "TeleDrive Share", 0, 0, false)) { inviteResult ->
            if (inviteResult is TdApi.ChatInviteLink) {
                callback(inviteResult.inviteLink)
            } else {
                callback(null)
            }
        }
    }

    fun getChatMembers(chatId: Long, callback: (List<TdApi.User>) -> Unit) {
        telegramClient.send(TdApi.GetChatAdministrators(chatId)) { adminResult ->
            if (adminResult is TdApi.ChatMembers) {
                val admins = adminResult.members
                val users = mutableListOf<TdApi.User>()
                var pending = admins.size
                
                if (pending == 0) {
                    callback(emptyList())
                    return@send
                }

                admins.forEach { member ->
                    telegramClient.send(TdApi.GetUser(member.memberId.let { (it as TdApi.MessageSenderUser).userId })) { userResult ->
                        if (userResult is TdApi.User) {
                            users.add(userResult)
                        }
                        pending--
                        if (pending == 0) {
                            callback(users)
                        }
                    }
                }
            } else {
                callback(emptyList())
            }
        }
    }

    fun shareFileToPhone(phoneNumber: String, messageId: Long, fromChatId: Long, callback: (Boolean, String?) -> Unit) {
        telegramClient.send(TdApi.SearchUserByPhoneNumber(phoneNumber, false)) { result ->
            if (result is TdApi.User) {
                val options = TdApi.MessageSendOptions().apply {
                    disableNotification = false
                    fromBackground = false
                }
                telegramClient.send(TdApi.ForwardMessages(
                    result.id,
                    null, // topicId
                    fromChatId,
                    longArrayOf(messageId),
                    options,
                    false,
                    false
                )) { forwardResult ->
                    if (forwardResult is TdApi.Messages) {
                        callback(true, null)
                    } else if (forwardResult is TdApi.Error) {
                        callback(false, forwardResult.message)
                    }
                }
            } else if (result is TdApi.Error) {
                callback(false, result.message)
            }
        }
    }
}
