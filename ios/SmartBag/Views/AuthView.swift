// AuthView.swift: signed out. Welcome, Create account and Log in. No forgot-password: there is no email service,
// so it is left out rather than faked. The password lives only in this screen's state while typing and goes to
// Bag It inside one call over the encrypted socket; it is never stored on the phone.

import SwiftUI

struct AuthFlow: View {
    enum Screen { case welcome, signUp, logIn }
    @State private var screen = Screen.welcome
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            switch screen {
            case .welcome:
                WelcomeScreen(onCreate: { go(.signUp) }, onLogIn: { go(.logIn) }).transition(.opacity)
            case .signUp:
                SignUpScreen(back: { go(.welcome) }, logIn: { go(.logIn) }).transition(move)
            case .logIn:
                LogInScreen(back: { go(.welcome) }, signUp: { go(.signUp) }).transition(move)
            }
        }
    }

    private var move: AnyTransition {
        reduceMotion ? .opacity : .asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .opacity)
    }
    private func go(_ s: Screen) { withAnimation(.easeInOut(duration: 0.25)) { screen = s } }
}

/// Local checks before the call, so most mistakes are caught under their own field.
enum AccountCheck {
    static func name(_ s: String) -> String? { s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Enter your name." : nil }
    static func email(_ s: String) -> String? {
        let e = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if e.isEmpty { return "Enter your email." }
        let parts = e.split(separator: "@", omittingEmptySubsequences: false)
        guard parts.count == 2, !parts[0].isEmpty, parts[1].contains("."), !parts[1].hasSuffix("."), !e.contains(" ") else {
            return "Enter a valid email address."
        }
        return nil
    }
    static func newPassword(_ s: String) -> String? { s.count < 8 ? "Use at least 8 characters for your password." : nil }
}

struct SignUpScreen: View {
    let back: () -> Void
    let logIn: () -> Void
    @EnvironmentObject var account: AccountModel
    @State private var name = ""
    @State private var email = ""
    @State private var password = ""
    @State private var errors: [String: String] = [:]
    @State private var serverError: String?
    @State private var busy = false

    var body: some View {
        VStack(spacing: 0) {
            FlowBar(back: back).padding(.horizontal, Space.margin).padding(.top, Space.s)
            ScrollView {
                VStack(alignment: .leading, spacing: Space.xl) {
                    ScreenTitle(title: "Create account").padding(.top, Space.l)
                    FormField(label: "Name", text: $name, error: errors["name"], content: .name)
                    FormField(label: "Email", text: $email, kind: .email, error: errors["email"], content: .emailAddress)
                    FormField(label: "Password", text: $password, kind: .password, hint: "At least 8 characters",
                              error: errors["password"], content: .newPassword)
                }
                .padding(.horizontal, Space.margin)
                .padding(.bottom, Space.xl)
                .disabled(busy)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .background(Theme.bg.ignoresSafeArea())
        .safeAreaInset(edge: .bottom, spacing: 0) {
            FormDock(title: "Create account", busy: busy, error: serverError, action: submit) {
                Button("Log in instead", action: logIn).buttonStyle(TextAction()).disabled(busy)
            }
        }
        .onDisappear { password = "" }
    }

    private func submit() {
        var e: [String: String] = [:]
        e["name"] = AccountCheck.name(name)
        e["email"] = AccountCheck.email(email)
        e["password"] = AccountCheck.newPassword(password)
        errors = e
        serverError = nil
        guard e.isEmpty else { return }
        busy = true
        Task {
            do {
                try await account.signUp(name: name, email: email.trimmingCharacters(in: .whitespacesAndNewlines), password: password)
                password = ""
            } catch {
                serverError = userMessage(error)
            }
            busy = false
        }
    }
}

struct LogInScreen: View {
    let back: () -> Void
    let signUp: () -> Void
    @EnvironmentObject var account: AccountModel
    @State private var email = ""
    @State private var password = ""
    @State private var errors: [String: String] = [:]
    @State private var serverError: String?
    @State private var busy = false

    var body: some View {
        VStack(spacing: 0) {
            FlowBar(back: back).padding(.horizontal, Space.margin).padding(.top, Space.s)
            ScrollView {
                VStack(alignment: .leading, spacing: Space.xl) {
                    ScreenTitle(title: "Log in").padding(.top, Space.l)
                    FormField(label: "Email", text: $email, kind: .email, error: errors["email"], content: .username)
                    FormField(label: "Password", text: $password, kind: .password, error: errors["password"], content: .password)
                }
                .padding(.horizontal, Space.margin)
                .padding(.bottom, Space.xl)
                .disabled(busy)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .background(Theme.bg.ignoresSafeArea())
        .safeAreaInset(edge: .bottom, spacing: 0) {
            FormDock(title: "Log in", busy: busy, error: serverError, action: submit) {
                Button("Create an account", action: signUp).buttonStyle(TextAction()).disabled(busy)
            }
        }
        .onDisappear { password = "" }
    }

    private func submit() {
        var e: [String: String] = [:]
        e["email"] = AccountCheck.email(email)
        e["password"] = password.isEmpty ? "Enter your password." : nil
        errors = e
        serverError = nil
        guard e.isEmpty else { return }
        busy = true
        Task {
            do {
                try await account.signIn(email: email.trimmingCharacters(in: .whitespacesAndNewlines), password: password)
                password = ""
            } catch {
                serverError = userMessage(error)
                password = ""                   // wrong password: type it again
            }
            busy = false
        }
    }
}
