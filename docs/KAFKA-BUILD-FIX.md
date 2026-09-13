# Kafka build fix

`CompanyEventPublisher` uses Jackson `ObjectMapper`. The project now declares `jackson-databind` explicitly because the Kafka starter does not guarantee Jackson availability for direct application imports.
