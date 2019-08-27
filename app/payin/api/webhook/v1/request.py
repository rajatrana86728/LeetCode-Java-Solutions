from app.commons.providers.stripe.stripe_models import Event


class StripeWebHookEvent(Event):
    """
    Wrapper used to imply that the internal stripe API Event model is mapped to this incoming request model
    """

    ...