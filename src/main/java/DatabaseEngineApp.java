@org.springframework.boot.SpringBootConfiguration
@org.springframework.boot.autoconfigure.EnableAutoConfiguration
@org.springframework.context.annotation.Import(DatabaseApiController.class)
public class DatabaseEngineApp {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("--cli")) {
            Client.start();
            return;
        }

        org.springframework.boot.SpringApplication.run(DatabaseEngineApp.class, args);
    }
}
