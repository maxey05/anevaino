package ph.anevaino

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class AnevainoApplication

fun main(args: Array<String>) {
    runApplication<AnevainoApplication>(*args)
}