# Shizuku launches this class in a separate shell process by the runtime class name.
# Keep its constructors and AIDL implementation available for reflective creation.
-keep class dev.simpilot.ShellUserService { public <init>(...); *; }
-keep class dev.simpilot.IShellService$Stub { *; }
-keep class dev.simpilot.IShellService$Stub$Proxy { *; }
