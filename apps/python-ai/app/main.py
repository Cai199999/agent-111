"""FastAPI 入口。默认 8700，仅内网；不直接访问子 Agent。"""

from __future__ import annotations

import logging
import os

from fastapi import FastAPI

from app.api.routes import router

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")


def create_app() -> FastAPI:
    app = FastAPI(title="wuye-agent-python-ai", version="0.1.0")
    app.include_router(router)
    return app


app = create_app()


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=os.getenv("AI_HOST", "127.0.0.1"),
        port=int(os.getenv("AI_PORT", "8700")),
        log_level="info",
    )
