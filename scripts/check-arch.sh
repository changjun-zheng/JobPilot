#!/usr/bin/env bash
# JobPilot 架构约束检查器
#
# 把 AGENTS.md §「端口/适配器边界」的约定变成可执行检查。
# 退出码：0 = 全部通过；1 = 有违规。
#
# 用法：
#   bash scripts/check-arch.sh              # 检查全部规则
#   bash scripts/check-arch.sh boundary     # 只跑某条规则
#
# 依赖：rg（必需）、ast-grep（可选，未安装时跳过 AST 类规则）

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/src/main/java"
ONLY="${1:-}"
FAILED=0

RG="$(command -v rg 2>/dev/null || true)"
# Windows bash（Git Bash / MSYS2）下 command -v 找不到 .exe，手动查一下
if [ -z "$RG" ] && command -v where.exe >/dev/null 2>&1; then
  RG="$(where.exe rg 2>/dev/null | head -1 | tr -d '\r')"
fi
AG="$(command -v ast-grep 2>/dev/null || true)"
if [ -z "$AG" ] && command -v where.exe >/dev/null 2>&1; then
  AG="$(where.exe ast-grep 2>/dev/null | head -1 | tr -d '\r')"
fi

if [ -z "$RG" ]; then
  # Linux / CI 上没有 winget，装一下再继续：缺工具而静默跳过架构检查，
  # 等于让「检查通过」变成「检查根本没跑」（CI 上就是这么红的）。
  if command -v apt-get >/dev/null 2>&1; then
    echo "未找到 rg，尝试通过 apt-get 安装…" >&2
    (apt-get update -qq && apt-get install -y -qq ripgrep) >/dev/null 2>&1 || true
    RG="$(command -v rg 2>/dev/null || true)"
  fi
fi

if [ -z "$RG" ]; then
  echo "错误：找不到 rg。装法：winget install BurntSushi.ripgrep.MSVC（Windows）/ apt-get install ripgrep（Linux）" >&2
  exit 2
fi

should_run() { [ -z "$ONLY" ] || [ "$ONLY" = "$1" ]; }

report() {
  local name="$1" desc="$2" hits="$3"
  if [ -z "$hits" ]; then
    printf '  \033[32m✓\033[0m %-28s %s\n' "$name" "$desc"
  else
    printf '  \033[31m✗\033[0m %-28s %s\n' "$name" "$desc"
    echo "$hits" | sed 's/^/      /'
    FAILED=1
  fi
}

echo "JobPilot 架构检查  ($SRC)"
echo ""

# ── 规则 1：Spring AI 类型只能出现在 ai/adapter ──────────────────
# 依据：AGENTS.md「Spring AI 与供应商 HTTP/SDK 类型只能出现在 ai.adapter」
# 用 rg 而非 ast-grep：Java 的 import 是嵌套 scoped_identifier，
# ast-grep 的 $$$ 不匹配路径段（实测确认）。
if should_run boundary; then
  hits="$("$RG" -n 'import org\.springframework\.ai\.' "$SRC" 2>/dev/null \
    | grep -vE '[/\\]adapter[/\\]' || true)"
  report "spring-ai-boundary" "Spring AI 类型仅限 ai/adapter/" "$hits"
fi

# ── 规则 2：Spring AI 类型名不得在非 adapter 层出现 ──────────────
# 补规则 1 的漏网之鱼：全限定名写法、无 import 的直接引用。
if should_run boundary; then
  hits=""
  for t in ChatModel EmbeddingModel ChatResponse EmbeddingResponse \
           ChatClient Prompt AssistantMessage SystemMessage UserMessage; do
    found="$("$RG" -n "\b${t}\b" "$SRC" 2>/dev/null \
      | grep -vE '[/\\]adapter[/\\]' || true)"
    [ -n "$found" ] && hits="${hits}${found}"$'\n'
  done
  hits="$(printf '%s' "$hits" | sed '/^$/d')"
  report "spring-ai-type-leak" "Spring AI 类型名未泄漏到业务层" "$hits"
fi

# ── 规则 3：不得出现参考项目的依赖 ──────────────────────────────
# 依据：AGENTS.md「不把 paicli / PaiSmart 加为 Maven、submodule 或源码依赖」
if should_run deps; then
  hits="$("$RG" -n 'import (com\.)?(paicli|paismart)\.' "$SRC" 2>/dev/null || true)"
  report "no-reference-imports" "未 import paicli / PaiSmart" "$hits"
fi

# ── 规则 4：业务层不得直接依赖 Elasticsearch ────────────────────
# 依据：AGENTS.md「不引入 Elasticsearch 替代 Chroma」
if should_run deps; then
  hits="$("$RG" -n 'import org\.elasticsearch\.' "$SRC" 2>/dev/null || true)"
  report "no-elasticsearch" "未引入 Elasticsearch" "$hits"
fi

# ── 规则 5：不要用 printStackTrace（应走 GlobalExceptionHandler）──
# 依据：AGENTS.md「不要向外泄漏堆栈」
if should_run quality; then
  hits="$("$RG" -n '\.printStackTrace\(\)' "$SRC" 2>/dev/null || true)"
  report "no-print-stacktrace" "未使用 printStackTrace()" "$hits"
fi

# ── 规则 6：AST 检查 — 禁止裸 new Thread ───────────────────────
# 依据：AGENTS.md「Agent 服务本质是高并发 IO + 线程池编排」
# 用 ast-grep：能区分代码里的 new Thread 和注释/字符串里的。
if should_run quality && [ -n "$AG" ]; then
  hits="$("$AG" run -p 'new Thread($$$)' -l java "$SRC" 2>/dev/null || true)"
  report "no-raw-thread" "未直接 new Thread（应用线程池）" "$hits"
fi

echo ""
if [ "$FAILED" -eq 0 ]; then
  printf '\033[32m全部通过\033[0m\n'
else
  printf '\033[31m有违规，见上\033[0m\n'
fi
exit "$FAILED"
