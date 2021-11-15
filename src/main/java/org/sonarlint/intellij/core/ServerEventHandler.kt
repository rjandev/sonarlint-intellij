/*
 * SonarLint for IntelliJ IDEA
 * Copyright (C) 2015-2021 SonarSource
 * sonarlint@sonarsource.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonarlint.intellij.core

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import org.sonarlint.intellij.config.global.ServerConnection

class ServerEventHandler {
    private var currentNotification: Notification? = null

    fun handle(serverConnection: ServerConnection, event: Event) {
        val text = when(event) {
            is RuleActivated -> "Rule activated: ${event.content.ruleKey}"
            else -> "Message received: $event"
        }
        showBalloon(serverConnection.name, text)
    }

    private fun showBalloon(connectionName: String, message: String) {
        currentNotification?.expire()
        val notification = ServerEventNotifications.GROUP.createNotification(
            "Event received from $connectionName",
            message,
            NotificationType.INFORMATION, null
        )
        notification.isImportant = true
        notification.notify(null)
        currentNotification = notification
    }
}
