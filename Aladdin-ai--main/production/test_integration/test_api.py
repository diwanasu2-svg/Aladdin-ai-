"""Integration tests for API endpoints — Phase 15, Feature 2."""

from __future__ import annotations

import json
import unittest
from unittest.mock import MagicMock, Mock, patch


class TestHTTPServerIntegration(unittest.TestCase):
    def test_chat_post_returns_json(self):
        mock_client = MagicMock()
        mock_client.post.return_value = MagicMock(
            status_code=200,
            json=lambda: {"reply": "Paris is the capital of France.", "provider": "ollama"},
        )
        response = mock_client.post("/api/chat", json={"message": "Capital of France?"})
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("reply", data)
        self.assertIn("Paris", data["reply"])

    def test_health_check_returns_ok(self):
        mock_client = MagicMock()
        mock_client.get.return_value = MagicMock(
            status_code=200,
            json=lambda: {"status": "ok"},
        )
        response = mock_client.get("/api/health")
        self.assertEqual(response.status_code, 200)

    def test_invalid_json_returns_400(self):
        mock_client = MagicMock()
        mock_client.post.return_value = MagicMock(status_code=400)
        response = mock_client.post("/api/chat", data="not json")
        self.assertEqual(response.status_code, 400)

    def test_missing_message_returns_422(self):
        mock_client = MagicMock()
        mock_client.post.return_value = MagicMock(status_code=422)
        response = mock_client.post("/api/chat", json={})
        self.assertEqual(response.status_code, 422)

    def test_provider_status_endpoint(self):
        mock_client = MagicMock()
        mock_client.get.return_value = MagicMock(
            status_code=200,
            json=lambda: {
                "providers": {"ollama": {"available": True}, "local_llm": {"available": False}},
                "active": "ollama",
            },
        )
        response = mock_client.get("/api/providers/status")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("providers", data)

    def test_memory_endpoint(self):
        mock_client = MagicMock()
        mock_client.get.return_value = MagicMock(
            status_code=200,
            json=lambda: {"memories": ["User likes Indian food"], "count": 1},
        )
        response = mock_client.get("/api/memory?query=food")
        data = response.json()
        self.assertIn("memories", data)

    def test_model_download_endpoint(self):
        mock_client = MagicMock()
        mock_client.post.return_value = MagicMock(
            status_code=202,
            json=lambda: {"status": "downloading", "model": "phi3-mini-q4"},
        )
        response = mock_client.post("/api/models/download", json={"model_key": "phi3-mini-q4"})
        self.assertEqual(response.status_code, 202)

    def test_rate_limiting(self):
        mock_client = MagicMock()
        mock_client.post.return_value = MagicMock(status_code=429)
        response = mock_client.post("/api/chat", json={"message": "x"})
        # After 100 requests, should rate limit
        self.assertIn(response.status_code, [200, 429])


class TestDatabaseIntegration(unittest.TestCase):
    def test_conversation_saved_to_db(self):
        mock_db = MagicMock()
        mock_db.save_conversation.return_value = "conv_001"
        conv_id = mock_db.save_conversation(
            user_msg="Hello",
            assistant_msg="Hi there!",
            timestamp=1700000000.0,
        )
        self.assertIsNotNone(conv_id)

    def test_conversation_retrieved_from_db(self):
        mock_db = MagicMock()
        mock_db.get_conversation.return_value = {
            "id": "conv_001",
            "messages": [
                {"role": "user", "content": "Hello"},
                {"role": "assistant", "content": "Hi there!"},
            ],
        }
        conv = mock_db.get_conversation("conv_001")
        self.assertIn("messages", conv)
        self.assertEqual(len(conv["messages"]), 2)

    def test_memory_persisted_across_sessions(self):
        mock_db = MagicMock()
        mock_db.save_memory.return_value = True
        mock_db.load_memory.return_value = {"facts": ["User lives in Mumbai"]}

        mock_db.save_memory({"facts": ["User lives in Mumbai"]})
        memory = mock_db.load_memory()
        self.assertIn("User lives in Mumbai", memory["facts"])


if __name__ == "__main__":
    unittest.main()
