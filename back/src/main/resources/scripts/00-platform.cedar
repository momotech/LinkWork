// ============================================================
// 00-platform.cedar — 平台级安全策略（最高优先级）
// ============================================================
// Cedar 原生语义：forbid 覆盖 permit，无匹配 permit → 隐式拒绝
// 评估模型：所有 .cedar 文件合并为单个 PolicySet，一次评估
// ============================================================

// ---- FORBID: 高危操作（覆盖所有 permit）----

// 文件系统破坏（精确匹配根目录，不误拦工作区路径）
forbid (
  principal,
  action == Action::"execute",
  resource
)
when {
  context.command like "*rm -rf /" ||
  context.command like "*rm -rf / *" ||
  context.command like "*rm -rf ~*" ||
  context.command like "*mkfs*" ||
  context.command like "*dd if=*of=/dev/*" ||
  context.command like "*chmod -R 777 /" ||
  context.command like "*chmod -R 777 / *" ||
  context.command like "*chown -R * /" ||
  context.command like "*chown -R * / *"
};

// [暂时禁用] 网络服务启动 / 反弹 shell
// forbid (
//   principal,
//   action == Action::"execute",
//   resource
// )
// when {
//   context.command like "*python* -m http.server*" ||
//   context.command like "*python* -m SimpleHTTPServer*" ||
//   context.command like "*nc -l*" ||
//   context.command like "*ncat *" ||
//   context.command like "*socat *" ||
//   context.command like "*/bin/bash -i*" ||
//   context.command like "*/bin/sh -i*"
// };

// [暂时禁用] 外发网络请求
// forbid (
//   principal,
//   action == Action::"execute",
//   resource
// )
// when {
//   context.command like "curl *" ||
//   context.command like "wget *"
// };

// 提权命令（危险 sudo 模式 + su）
forbid (
  principal,
  action == Action::"execute",
  resource
)
when {
  context.command like "sudo *su*" ||
  context.command like "sudo */bin/bash*" ||
  context.command like "sudo *-i*" ||
  context.command like "su *" ||
  context.command == "su"
};

// Docker / K8s 操作（需要额外岗位授权）
forbid (
  principal,
  action == Action::"execute",
  resource
)
when {
  context.command_type == "docker" ||
  context.command_type == "k8s"
};

// ---- PERMIT: 允许的命令类型 ----

// Shell 命令（ls, cat, grep, echo, whoami, mkdir, cp, mv, etc.）
permit (
  principal,
  action == Action::"execute",
  resource
)
when { context.command_type == "shell" };

// Git 操作（git, gh, gitlab）
permit (
  principal,
  action == Action::"execute",
  resource
)
when { context.command_type == "git" };

// 包管理器（npm, pip, go, cargo, etc.）
permit (
  principal,
  action == Action::"execute",
  resource
)
when { context.command_type == "package" };
