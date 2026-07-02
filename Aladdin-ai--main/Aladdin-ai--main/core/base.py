from abc import ABC, abstractmethod

class BaseModule(ABC):
    @abstractmethod
    def start(self):
        pass

    @abstractmethod
    def stop(self):
        pass

    @abstractmethod
    def status(self) -> dict:
        pass

class BaseMemory(ABC):
    @abstractmethod
    def save(self, key: str, value: any):
        pass

    @abstractmethod
    def load(self, key: str) -> any:
        pass

    @abstractmethod
    def clear(self):
        pass
