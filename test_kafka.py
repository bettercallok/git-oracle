from kafka import KafkaConsumer
try:
    c = KafkaConsumer(bootstrap_servers=['localhost:9092'], api_version=(0,10))
    print(c.topics())
except Exception as e:
    print(e)
