"""ASGI entry point: `uvicorn model_gateway.server:app`. Requires the OIDC settings in the environment."""
from .main import create_app

app = create_app()
