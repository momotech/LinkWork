"""AI 员工启动脚本（生产版本）。

职责：创建 LinkWork Worker 并启动任务消费循环。
不负责 zzd 生命周期管理（由 start.sh 负责）。

环境变量：
    WORKSTATION_ID  - 必填，工位 ID
    REDIS_URL       - 可选，默认 redis://redis:6379
    CONFIG_FILE     - 可选，默认 /opt/agent/config.json
    LOG_LEVEL       - 可选，默认 INFO
"""

from __future__ import annotations

import asyncio
import logging
import os
import signal
import sys

from linkwork_executor.work.worker import Worker

# ---------------------------------------------------------------------------
# 日志配置
# ---------------------------------------------------------------------------
_log_level = os.getenv("LOG_LEVEL", "INFO").upper()
logging.basicConfig(
    level=getattr(logging, _log_level, logging.INFO),
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
    stream=sys.stdout,
)
logger = logging.getLogger("ai_employee")


# ---------------------------------------------------------------------------
# 信号处理
# ---------------------------------------------------------------------------
_shutdown_event: asyncio.Event | None = None


def _handle_signal(signum: int, _frame: object) -> None:
    """SIGTERM / SIGINT → 设置 shutdown event，让 asyncio 循环优雅退出。"""
    sig_name = signal.Signals(signum).name
    logger.info("收到信号 %s，准备优雅关闭...", sig_name)
    if _shutdown_event is not None:
        _shutdown_event.set()


# ---------------------------------------------------------------------------
# 主逻辑
# ---------------------------------------------------------------------------
async def main() -> None:
    global _shutdown_event
    _shutdown_event = asyncio.Event()

    config_file = os.getenv("CONFIG_FILE", "/opt/agent/config.json")
    workstation_id = os.getenv("WORKSTATION_ID", "")

    logger.info("========== AI 员工启动 ==========")
    logger.info("工位 ID:   %s", workstation_id or "未设置")
    logger.info("Redis:     %s", os.getenv("REDIS_URL", "redis://redis:6379 (默认)"))
    logger.info("配置文件:  %s", config_file)
    logger.info("日志级别:  %s", _log_level)
    logger.info("==================================")

    if not workstation_id:
        logger.error("WORKSTATION_ID 未设置，无法启动")
        sys.exit(1)

    if not os.path.isfile(config_file):
        logger.error("配置文件不存在: %s", config_file)
        sys.exit(1)

    worker = Worker(config_file)

    # 将 Worker 循环和 shutdown 事件并行等待
    worker_task = asyncio.create_task(_run_worker(worker))
    shutdown_task = asyncio.create_task(_shutdown_event.wait())

    done, pending = await asyncio.wait(
        {worker_task, shutdown_task},
        return_when=asyncio.FIRST_COMPLETED,
    )

    # 取消尚未完成的任务
    for task in pending:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass

    # 检查 worker 是否异常退出
    if worker_task in done:
        exc = worker_task.exception()
        if exc is not None:
            logger.exception("Worker 异常退出: %s", exc)
            sys.exit(1)

    logger.info("AI 员工已停止")


async def _run_worker(worker: Worker) -> None:
    """运行 Worker 消费循环。"""
    await worker.run_forever()


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    # 注册信号处理（在 asyncio 之前）
    signal.signal(signal.SIGTERM, _handle_signal)
    signal.signal(signal.SIGINT, _handle_signal)

    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logger.info("AI 员工已停止 (KeyboardInterrupt)")
        sys.exit(0)
    except Exception:
        logger.exception("AI 员工异常退出")
        sys.exit(1)
