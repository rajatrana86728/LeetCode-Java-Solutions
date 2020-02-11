from structlog.stdlib import BoundLogger

from app.purchasecard.core.utils import enriched_error_parse_int
from app.purchasecard.repository.delivery_funding import (
    DeliveryFundingRepositoryInterface,
)
from app.purchasecard.repository.marqeta_transaction import (
    MarqetaTransactionRepositoryInterface,
)
from app.purchasecard.constants import BUFFER_MULTIPLIER_FOR_DELIVERY


class TransactionProcessor:
    def __init__(
        self,
        logger: BoundLogger,
        marqeta_repository: MarqetaTransactionRepositoryInterface,
        delivery_funding_repository: DeliveryFundingRepositoryInterface,
    ):
        self.logger = logger
        self.marqeta_repository = marqeta_repository
        self.delivery_funding_repository = delivery_funding_repository

    async def get_funded_amount_by_delivery_id(self, delivery_id: str) -> int:
        result = await self.marqeta_repository.get_funded_amount_by_delivery_id(
            enriched_error_parse_int(delivery_id, "delivery id")
        )
        return result

    async def get_fundable_amount_by_delivery_id(
        self, delivery_id: str, restaurant_total: int
    ) -> int:
        """
        base amount + delivery fundings - fundings already used. Now we assume this endpoint is for non-partners only
        * the base amount for non-partners is the estimate + buffer
        * the base amount for partners is 0
        """
        parsed_delivery_id = enriched_error_parse_int(delivery_id, "delivery id")
        funded_amount_for_delivery = await self.marqeta_repository.get_funded_amount_by_delivery_id(
            parsed_delivery_id
        )
        total_funding_for_delivery = await self.delivery_funding_repository.get_total_funding_by_delivery_id(
            parsed_delivery_id
        )
        amount = (
            int(BUFFER_MULTIPLIER_FOR_DELIVERY * restaurant_total)
            + total_funding_for_delivery
        )
        return amount - funded_amount_for_delivery

    async def has_associated_marqeta_transaction(
        self, delivery_id: str, ignore_timed_out: bool
    ) -> bool:
        parsed_delivery_id = enriched_error_parse_int(delivery_id, "delivery id")
        return await self.marqeta_repository.has_associated_marqeta_transaction(
            parsed_delivery_id, ignore_timed_out
        )
