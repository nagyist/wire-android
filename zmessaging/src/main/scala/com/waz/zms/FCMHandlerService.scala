/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zms

import android.util.Base64
import com.google.firebase.messaging.{FirebaseMessagingService, RemoteMessage}
import com.waz.HockeyApp
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.model._
import com.waz.service.conversation.ConversationsContentUpdater
import com.waz.service.otr.OtrService
import com.waz.service.push.PushService
import com.waz.service.{ZMessaging, ZmsLifecycle}
import com.waz.sync.client.PushNotification
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.{JsonDecoder, LoggedTry}
import org.json
import org.json.JSONObject

import scala.collection.JavaConverters._
import scala.concurrent.Future

/**
  * For more information, see: https://firebase.google.com/docs/cloud-messaging/android/receive
  */
class FCMHandlerService extends FirebaseMessagingService with ZMessagingService {
  import com.waz.threading.Threading.Implicits.Background

  lazy val pushSenderId = ZMessaging.currentGlobal.backend.pushSenderId
  lazy val accounts = ZMessaging.currentAccounts

  /**
    * According to the docs, we have 10 seconds to process notifications upon receiving the `remoteMessage`.
    * This should be plenty of time (?)
    */
  override def onMessageReceived(remoteMessage: RemoteMessage) = {

    import com.waz.zms.FCMHandlerService._

    Option(remoteMessage.getData).map(_.asScala.toMap).foreach { data =>
      verbose(s"onMessageReceived with data: $data")

      Option(ZMessaging.currentGlobal) match {
        case Some(glob) if glob.backend.pushSenderId == remoteMessage.getFrom =>

          data.get(UserKey).map(UserId) match {
            case Some(target) =>

              accounts.loggedInAccounts.head.flatMap { accs =>
                accs.find(_.userId.exists(_ == target)).map(_.id) match {
                  case Some(acc) =>
                    accounts.getZMessaging(acc).flatMap {
                      case Some(zms) => FCMHandler.apply(zms, data)
                      case _ =>
                        warn("Couldn't instantiate zms instance")
                        Future.successful({})
                    }
                  case None =>
                    warn("Could not find target account for notification")
                    Future.successful({})
                }
              }
          }
        case None =>
          HockeyApp.saveException(new Exception(UserKeyMissingMsg), UserKeyMissingMsg)
          Future.successful({})

        case Some(_) =>
          warn(s"Received FCM notification from unknown sender: ${remoteMessage.getFrom}. Ignoring...")
          Future.successful({})
        case None =>
          warn("No ZMessaging global available - calling too early")
          Future.successful({})
      }
    }
  }

  /**
    * Called when the device hasn't connected to the FCM server in over 1 month, or there are more than 100 FCM
    * messages available for this device on the FCM servers.
    *
    * Since we have our own missing notification tracking on websocket, we should be able to ignore this.
    */
  override def onDeletedMessages() = warn("onDeleteMessages")
}

object FCMHandlerService {

  val UserKeyMissingMsg = "Notification did not contain user key - discarding"

  class FCMHandler(otrService:   OtrService,
                   lifecycle:    ZmsLifecycle,
                   push:         PushService,
                   self:         UserId,
                   convsContent: ConversationsContentUpdater) {

    private implicit val dispatcher = new SerialDispatchQueue(name = "FCMHandler")

    def handleMessage(data: Map[String, String]): Future[Unit] = {
      data match {
        case CipherNotification(content, mac) =>
          decryptNotification(content, mac) flatMap {
            case Some(notification) =>
              addNotificationToProcess(notification.id)
            case None =>
              warn(s"gcm decoding failed: triggering notification history sync in case this notification is for us.")
              push.syncHistory()
          }

        case PlainNotification(notification) =>
          addNotificationToProcess(notification.id)

        case NoticeNotification(nId) =>
          addNotificationToProcess(nId)

        case _ => warn(s"Unexpected notification"); Future.successful({})
      }
    }

    private def decryptNotification(content: Array[Byte], mac: Array[Byte]) =
      otrService.decryptCloudMessage(content, mac) map {
        case Some(DecryptedNotification(notification)) => Some(notification)
        case _ => None
      }

    private def addNotificationToProcess(nId: Uid): Future[Unit] = {
      lifecycle.active.head flatMap {
        case true => Future.successful(()) // no need to process GCM when ui is active
        case _ =>
          verbose(s"addNotification: $nId")
          Future.successful(push.cloudPushNotificationsToProcess.mutate(_ + nId))
      }
    }
  }

  object FCMHandler {
    def apply(zms: ZMessaging, data: Map[String, String]): Future[Unit] =
      new FCMHandler(zms.otrService, zms.lifecycle, zms.push, zms.selfUserId, zms.convsContent).handleMessage(data)
  }

  val DataKey = "data"
  val UserKey = "user"
  val TypeKey = "type"
  val MacKey  = "mac"

  object CipherNotification {
    def unapply(data: Map[String, String]): Option[(Array[Byte], Array[Byte])] =
      (data.get(TypeKey), data.get(DataKey), data.get(MacKey)) match {
        case (Some("otr" | "cipher"), Some(content), Some(mac)) =>
          LoggedTry((Base64.decode(content, Base64.NO_WRAP | Base64.NO_CLOSE), Base64.decode(mac, Base64.NO_WRAP | Base64.NO_CLOSE))).toOption
        case _ => None
      }
  }

  object PlainNotification {
    def unapply(data: Map[String, String]): Option[PushNotification] =
      data.get(DataKey) match {
        case Some(content) => LoggedTry(PushNotification.NotificationDecoder(new JSONObject(content))).toOption
        case _ => None
      }
  }

  object NoticeNotification {
    def unapply(data: Map[String, String]): Option[Uid] =
      (data.get(TypeKey), data.get(DataKey)) match {
        case (Some("notice"), Some(content)) => LoggedTry(JsonDecoder.decodeUid('id)(new json.JSONObject(content))).toOption
        case _ => None
    }
  }

  object DecryptedNotification {
    def unapply(js: JSONObject): Option[PushNotification] = LoggedTry(PushNotification.NotificationDecoder(js.getJSONObject("data"))).toOption
  }
}
