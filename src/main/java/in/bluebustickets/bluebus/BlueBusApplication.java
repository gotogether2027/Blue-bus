package in.bluebustickets.bluebus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;

@SpringBootApplication(exclude = RabbitAutoConfiguration.class)
public class BlueBusApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlueBusApplication.class, args);
    }
}
