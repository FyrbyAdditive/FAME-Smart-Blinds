import SwiftUI

struct AboutView: View {
    @Environment(\.dismiss) private var dismiss

    private var appName: String { "FAME Smart Blinds" }

    private var versionText: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1"
        return "Version \(version) (\(build))"
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Spacer()

                // Company Logo
                Image("CompanyLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(height: 80)
                    .padding(.bottom, 8)

                // App Name with gradient
                Text(appName)
                    .font(.system(size: 28, weight: .bold, design: .rounded))
                    .foregroundStyle(
                        LinearGradient(
                            colors: [.blue, .cyan],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                    .padding(.top, 16)

                Text(versionText)
                    .font(.system(size: 13, weight: .medium, design: .monospaced))
                    .foregroundColor(.secondary)
                    .padding(.top, 4)

                // Description
                Text("Smart blind control for your home")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .padding(.top, 12)

                // Feature badges
                HStack(spacing: 12) {
                    FeatureBadge(icon: "wifi", text: "WiFi")
                    FeatureBadge(icon: "slider.horizontal.3", text: "Control")
                    FeatureBadge(icon: "arrow.up.arrow.down", text: "Calibrate")
                }
                .padding(.top, 16)

                Spacer()

                // Footer
                VStack(spacing: 4) {
                    Divider()
                        .padding(.horizontal, 40)
                        .padding(.bottom, 8)

                    Text("© 2025 Fyrby Additive Manufacturing & Engineering")
                        .font(.caption)
                        .foregroundColor(.secondary)

                    Link(destination: URL(string: "https://fyrbyadditive.com")!) {
                        HStack(spacing: 4) {
                            Image(systemName: "globe")
                            Text("fyrbyadditive.com")
                        }
                        .font(.caption)
                    }
                }
                .padding(.bottom, 12)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle("About")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
        }
        #if targetEnvironment(macCatalyst)
        .frame(width: 320, height: 380)
        #endif
    }
}

struct FeatureBadge: View {
    let icon: String
    let text: String

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: icon)
                .font(.caption)
            Text(text)
                .font(.caption)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color.accentColor.opacity(0.1))
        )
        .foregroundColor(.accentColor)
    }
}

#Preview {
    AboutView()
}
