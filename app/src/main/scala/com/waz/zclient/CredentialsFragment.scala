/**
 * Wire
 * Copyright (C) 2018 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient

import android.graphics.Color
import android.os.Bundle
import android.view.{LayoutInflater, View, ViewGroup}
import android.widget.TextView
import com.waz.ZLog.ImplicitTag._
import com.waz.api.impl.ErrorResponse
import com.waz.content.UserPreferences
import com.waz.content.UserPreferences.{PendingEmail, PendingPassword}
import com.waz.model.AccountData.Password
import com.waz.model.EmailAddress
import com.waz.service.AccountManager.ClientRegistrationState.LimitReached
import com.waz.service.{AccountManager, AccountsService}
import com.waz.threading.Threading.Implicits.Ui
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.events.Signal
import com.waz.utils.{returning, _}
import com.waz.zclient.common.controllers.BrowserController
import com.waz.zclient.common.controllers.global.KeyboardController
import com.waz.zclient.newreg.views.PhoneConfirmationButton
import com.waz.zclient.newreg.views.PhoneConfirmationButton.State.{CONFIRM, NONE}
import com.waz.zclient.pages.main.profile.validator.{EmailValidator, PasswordValidator}
import com.waz.zclient.pages.main.profile.views.GuidedEditText
import com.waz.zclient.ui.utils.TextViewUtils
import com.waz.zclient.utils.ContextUtils.showToast
import com.waz.zclient.utils._
import com.waz.zclient.views.LoadingIndicatorView
import com.waz.znet.Response.Status

import scala.concurrent.Future

//Do not rely on having ZMS!
abstract class CredentialsFragment extends FragmentHelper {

  import CredentialsFragment._

  lazy val am       = inject[Signal[AccountManager]]
  lazy val accounts = inject[AccountsService]
  lazy val spinner  = inject[SpinnerController]
  lazy val keyboard = inject[KeyboardController]

  def hasPw        = getBooleanArg(HasPasswordArg)
  def displayEmail = getStringArg(EmailArg).map(EmailAddress)

  override def onPause(): Unit = {
    keyboard.hideKeyboardIfVisible()
    super.onPause()
  }

  def showError(err: ErrorResponse) = { //this method is needed to avoid a compiler error - don't inline
    spinner.hideSpinner()
    //getContainer.showError(EntryError(error.getCode, error.getLabel, SignInMethod(Register, Email)))
    showToast(err match { // TODO show proper dialog...
      case _@ErrorResponse(Status.Forbidden, _, "invalid-credentials") => "Password incorrect - please try again"
      case _ => s"Something went wrong, please try again: $err"
    })
  }

  def activity = getActivity.asInstanceOf[MainActivity]

  override def onBackPressed() = true // can't go back...
}

object CredentialsFragment {

  val HasPasswordArg = "HAS_PASSWORD_ARG"
  val EmailArg       = "EMAIL_ARG"

  def apply[A <: CredentialsFragment](f: A, hasPassword: Boolean, email: Option[EmailAddress] = None): A = {
    f.setArguments(returning(new Bundle()) { b =>
      email.map(_.str).foreach(b.putString(EmailArg, _))
      b.putBoolean(HasPasswordArg, hasPassword)
    })
    f
  }
}

class AddEmailFragment extends CredentialsFragment {
  import Threading.Implicits.Ui

  lazy val emailValidator = EmailValidator.newInstance()
  lazy val email = Signal(Option.empty[EmailAddress])

  lazy val isValid = email.map {
    case Some(e) => emailValidator.validate(e.str)
    case _ => false
  }

  lazy val backButton = returning(view[View](R.id.back_button)) { vh =>
    vh.onClick { _ =>
      for {
        am <- am.head
        _  <- am.storage.userPrefs(PendingEmail) := None
        _  <- accounts.logout(am.userId)
      } yield activity.startFirstFragment() // send user back to login screen
    }
  }

  lazy val confirmationButton = returning(view[PhoneConfirmationButton](R.id.confirmation_button)) { vh =>
    vh.onClick { _ =>
      spinner.showSpinner(LoadingIndicatorView.Spinner)
      for {
        am      <- am.head
        Some(e) <- email.head
        resp    <- am.setEmail(e)
        _       <- resp match {
          case Right(_) => am.storage.userPrefs(PendingEmail) := Some(e)
          case Left(_) => Future.successful({})
        }
      } yield resp match {
        case Right(_) =>
          keyboard.hideKeyboardIfVisible()
          activity.replaceMainFragment(VerifyEmailFragment(e, hasPassword = hasPw), VerifyEmailFragment.Tag)
        case Left(err) => showError(err)
      }
    }

    isValid.map( if(_) CONFIRM else NONE).onUi ( st => vh.foreach(_.setState(st)))
  }


  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle) =
    inflater.inflate(R.layout.fragment_main_start_add_email, container, false)

  override def onViewCreated(view: View, savedInstanceState: Bundle) = {
    super.onViewCreated(view, savedInstanceState)

    Option(findById[GuidedEditText](view, R.id.email_field)).foreach { field =>
      field.setValidator(emailValidator)
      field.setResource(R.layout.guided_edit_text_sign_in__email)
      field.getEditText.addTextListener(txt => email ! Some(EmailAddress(txt)))
    }

    backButton
    confirmationButton.foreach(_.setAccentColor(Color.WHITE))
  }
}

object AddEmailFragment {
  val Tag = implicitLogTag

  def apply(hasPassword: Boolean = false): AddEmailFragment =
    CredentialsFragment(new AddEmailFragment(), hasPassword)
}


class VerifyEmailFragment extends CredentialsFragment {

  import com.waz.threading.Threading.Implicits.Ui

  lazy val resendTextView = returning(view[TextView](R.id.ttv__pending_email__resend)) { vh =>
    vh.onClick { _ =>
      didntGetEmailTextView.foreach(_.animate.alpha(0).start())
      vh.foreach(_.animate.alpha(0).withEndAction(new Runnable() {
        def run(): Unit = {
          vh.foreach(_.setEnabled(false))
        }
      }).start())
      displayEmail.foreach(accounts.requestVerificationEmail)
    }
  }

  lazy val didntGetEmailTextView = view[TextView](R.id.ttv__sign_up__didnt_get)

  lazy val backButton = returning(view[View](R.id.ll__activation_button__back)) { vh =>
    vh.onClick(_ => back())
  }

  private var emailChecking: CancellableFuture[Unit] = _

  override def onCreateView(inflater: LayoutInflater, viewGroup: ViewGroup, savedInstanceState: Bundle): View =
    inflater.inflate(R.layout.fragment_main_start_verify_email, viewGroup, false)

  override def onViewCreated(view: View, savedInstanceState: Bundle) = {
    super.onViewCreated(view, savedInstanceState)
    resendTextView
    didntGetEmailTextView
    backButton

    Option(findById[TextView](view, R.id.ttv__sign_up__check_email)).foreach { v =>
      displayEmail.foreach { e =>
        v.setText(getResources.getString(R.string.profile__email__verify__instructions, e.str))
        TextViewUtils.boldText(v)
      }
    }
  }

  override def onStart(): Unit = {
    super.onStart()
    emailChecking = for {
      am           <- CancellableFuture.lift(am.head)
      pendingEmail <- CancellableFuture.lift(am.storage.userPrefs(UserPreferences.PendingEmail).apply())
      resp         <- pendingEmail.fold2(CancellableFuture.successful(Left(ErrorResponse.internalError("No pending email set"))), am.checkEmailActivation)
      _ <- CancellableFuture.lift(resp.fold(e => Future.successful(Left(e)), _ =>
        for {
          _ <- am.storage.userPrefs(UserPreferences.PendingEmail) := None
          _ <- am.storage.userPrefs(UserPreferences.PendingPassword) := true
        } yield {}
      ))
    } yield resp match {
      case Right(_) => activity.replaceMainFragment(SetOrRequestPasswordFragment(pendingEmail.get, hasPw), SetOrRequestPasswordFragment.Tag)
      case Left(err) => showError(err)
    }
  }

  override def onStop(): Unit = {
    super.onStop()
    emailChecking.cancel()
  }

  private def back() = activity.replaceMainFragment(AddEmailFragment(hasPw), AddEmailFragment.Tag)

  override def onBackPressed(): Boolean = {
    back()
    true
  }
}

object VerifyEmailFragment {

  val Tag = implicitLogTag

  def apply(email: EmailAddress, hasPassword: Boolean = false): VerifyEmailFragment =
    CredentialsFragment(new VerifyEmailFragment(), hasPassword, Some(email))
}

class SetOrRequestPasswordFragment extends CredentialsFragment {

  lazy val password = Signal(Option.empty[Password])
  lazy val passwordValidator = PasswordValidator.instance(getContext)

  lazy val isValid = password.map {
    case Some(p) => passwordValidator.validate(p.str)
    case _ => false
  }

  lazy val confirmationButton = returning(view[PhoneConfirmationButton](R.id.confirmation_button)) { vh =>
    vh.onClick { _ =>
      spinner.showSpinner(LoadingIndicatorView.Spinner)
      for {
        am       <- am.head
        Some(pw) <- password.head //pw should be defined
      } yield
        if (hasPw) am.getOrRegisterClient(Some(pw)).map {
          case Right(state) =>
            (am.storage.userPrefs(PendingPassword) := false).map { _ =>
              keyboard.hideKeyboardIfVisible()
              state match {
                case LimitReached => activity.replaceMainFragment(OtrDeviceLimitFragment.newInstance, OtrDeviceLimitFragment.Tag, addToBackStack = false)
                case _ => activity.startFirstFragment()
              }
            }
          case Left(err) => showError(err)
        } else
          am.setPassword(pw).flatMap { //TODO perform login request after setting password in case the user has reset their password in the meantime
            case Right(_)  => (am.storage.userPrefs(PendingPassword) := false).map(_ => Right({}))
            case Left(err) => Future.successful(Left(err))
          }.map {
            case Right(_) => activity.startFirstFragment()
            case Left(err) => showError(err)
          }
    }

    isValid.map( if(_) CONFIRM else NONE).onUi ( st => vh.foreach(_.setState(st)))
  }

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle) =
    inflater.inflate(R.layout.fragment_main_start_set_password, container, false)

  override def onViewCreated(view: View, savedInstanceState: Bundle) = {

    val header = getString(if (hasPw) R.string.provide_password else R.string.add_password)
    val info   = getString(if (hasPw) R.string.provide_password_explanation else R.string.add_email_address_explanation, displayEmail.map(_.str).getOrElse("no_email_set"))

    findById[TextView](getView, R.id.info_text_header).setText(header)
    findById[TextView](getView, R.id.info_text).setText(info)

    Option(findById[GuidedEditText](getView, R.id.password_field)).foreach { field =>
      field.setValidator(passwordValidator)
      field.setResource(R.layout.guided_edit_text_sign_in__password)
      field.getEditText.addTextListener(txt => password ! Some(Password(txt)))
    }

    confirmationButton.foreach(_.setAccentColor(Color.WHITE))

    Option(findById[View](R.id.ttv_signin_forgot_password)).foreach { forgotPw =>
      forgotPw.onClick(inject[BrowserController].openUrl(getString(R.string.url_password_reset)))
      forgotPw.setVisible(hasPw)
    }
  }
}

object SetOrRequestPasswordFragment {
  val Tag = implicitLogTag

  def apply(email: EmailAddress, hasPassword: Boolean = false): SetOrRequestPasswordFragment =
    CredentialsFragment(new SetOrRequestPasswordFragment(), hasPassword, Some(email))
}
