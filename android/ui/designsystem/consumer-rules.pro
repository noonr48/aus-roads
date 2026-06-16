# Consumer ProGuard rules for :ui:designsystem.
# Compose pulls in its own rules from the AAR. We only need module-specific keeps here.

# Keep the design system tokens so reflection-based Compose previews still work.
-keep class au.com.ausroads.ui.designsystem.** { *; }
